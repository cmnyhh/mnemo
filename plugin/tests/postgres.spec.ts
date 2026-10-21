import { PGlite } from '@electric-sql/pglite'
import { describe, expect, it } from 'vitest'

import { createFactStore, extractFacts } from '../src/facts.ts'
import { createPostgresFactStore, createPostgresStore } from '../src/postgres.ts'
import type { Sql } from '../src/sql.ts'
import { migrateMemorySchema } from '../src/sql.ts'
import { createInMemoryStore } from '../src/store.ts'
import { captureSegments } from '../src/capture.ts'
import type { MemoryRecord } from '../src/types.ts'

/**
 * Run the adapter against a real Postgres engine (compiled to WASM), not a
 * recording double.
 *
 * A fake that returns canned rows would pass while the SQL was wrong — the
 * unique index, the `jsonb` encoding, and the `any($1::text[])` casts are
 * exactly the parts a double cannot check.
 * @returns the execution port and a teardown.
 */
async function engine(): Promise<{ sql: Sql; close: () => Promise<void>; db: PGlite }> {
  const db = new PGlite()
  const sql: Sql = {
    // The port is generic so a caller can name its row shape; a fake that drops
    // the type parameter would compile here and lose the check the adapter
    // relies on, so the generic is carried through the cast instead.
    async query<Row extends object = Readonly<Record<string, unknown>>>(
      text: string,
      params?: readonly unknown[],
    ) {
      const result = await db.query(text, params as unknown[] | undefined)
      return { rows: result.rows as readonly Row[] }
    },
  }
  await migrateMemorySchema(sql)
  return { sql, close: () => db.close(), db }
}

let counter = 0

/**
 * Capture one exchange into records, as `turn/end` does.
 * @param turn - turn number.
 * @param prompt - human message.
 * @param reply - assistant reply.
 * @returns records ready for `store.record`.
 */
function recordsOf(turn: number, prompt: string, reply: string): readonly MemoryRecord[] {
  return captureSegments(prompt, [reply], 240).map(segment => {
    counter += 1
    const facts = extractFacts(segment.text).asserted
    return {
      id: `r-${String(counter)}`,
      sessionId: 'session',
      kind: 'turn',
      text: segment.text,
      ...(segment.context === undefined ? {} : { context: segment.context }),
      createdAt: turn * 1000,
      turn,
      ...(facts.length === 0 ? {} : { facts }),
    }
  })
}

describe('postgres store', () => {
  it('recalls the same records after a restart, with the same scores', async () => {
    const { sql, close } = await engine()
    try {
      const first = createPostgresStore({ sql, maxRecords: 100, identifierWeight: 1.5 })
      await first.ready()
      for (const record of recordsOf(1, '部署端口固定为 8080，改动要走变更流程。', '好的。')) {
        first.record(record)
      }
      await first.flush()

      const second = createPostgresStore({ sql, maxRecords: 100, identifierWeight: 1.5 })
      await second.ready()
      expect(second.count()).toBe(first.count())

      const query = { text: '部署端口是多少', limit: 6, minScore: 0.4 }
      const before = first.recall(query)
      const after = second.recall(query)
      expect(after.length).toBeGreaterThan(0)
      expect(after.map(hit => [hit.record.id, hit.score]))
        .toEqual(before.map(hit => [hit.record.id, hit.score]))
    } finally {
      await close()
    }
  })

  it('keeps the fact layer authoritative across a restart', async () => {
    const { sql, close } = await engine()
    try {
      const first = createPostgresFactStore({ sql, maxFacts: 100 })
      await first.ready()
      first.apply('session', 1, 1000, extractFacts('部署端口固定为 8080。'))
      await first.flush()

      const second = createPostgresFactStore({ sql, maxFacts: 100 })
      await second.ready()
      second.apply('session', 2, 2000, extractFacts('端口改成 9090 了，8080 不用了'))
      // Before the flush the mirror already answers; the point of the test is
      // that it answers the same way once the rows reach the table.
      expect(second.query('session', '端口是多少').active.map(fact => fact.object)).toEqual(['9090'])
      await second.flush()

      const third = createPostgresFactStore({ sql, maxFacts: 100 })
      await third.ready()
      expect(third.query('session', '端口是多少').active.map(fact => fact.object)).toEqual(['9090'])
      expect(third.query('session', '之前端口是多少').superseded.map(fact => fact.object))
        .toEqual(['8080'])
    } finally {
      await close()
    }
  })

  it('answers each session from its own facts after a restart', async () => {
    const { sql, close } = await engine()
    try {
      const first = createPostgresFactStore({ sql, maxFacts: 100 })
      await first.ready()
      first.apply('project-a', 1, 1000, extractFacts('部署端口固定为 8080。'))
      first.apply('project-b', 1, 1000, extractFacts('部署端口固定为 9090。'))
      await first.flush()

      const second = createPostgresFactStore({ sql, maxFacts: 100 })
      await second.ready()
      expect(second.query('project-a', '端口是多少').active.map(fact => fact.object)).toEqual(['8080'])
      expect(second.query('project-b', '端口是多少').active.map(fact => fact.object)).toEqual(['9090'])
    } finally {
      await close()
    }
  })

  it('closes a fact that has not been committed yet', async () => {
    const { sql, close } = await engine()
    try {
      const store = createPostgresFactStore({ sql, maxFacts: 100 })
      await store.ready()
      store.apply('session', 1, 1000, extractFacts('部署端口固定为 8080。'))
      store.apply('session', 2, 2000, extractFacts('端口改成 9090 了，8080 不用了'))
      // Nothing has been flushed: the close has no row to update, so it has to
      // edit the queued insert instead. Getting this wrong leaves both values
      // current in the table while the mirror shows one, and the divergence only
      // surfaces after a restart.
      await store.flush()
      const { rows } = await sql.query<{ object: string }>(
        'select object from memory_fact where valid_to is null order by object',
      )
      expect(rows.map(row => row.object)).toEqual(['9090'])
    } finally {
      await close()
    }
  })

  it('survives a replayed flush without duplicating rows', async () => {
    const { sql, close } = await engine()
    try {
      const store = createPostgresStore({ sql, maxRecords: 100, identifierWeight: 1.5 })
      await store.ready()
      for (const record of recordsOf(1, '配色方案偏冷，用蓝灰。', '好的。')) store.record(record)
      await store.flush()
      // A crash between commit and queue clear replays the same ids.
      const { rows } = await sql.query<{ count: string }>('select count(*)::text as count from memory_record')
      const before = rows[0]?.count
      const again = createPostgresStore({ sql, maxRecords: 100, identifierWeight: 1.5 })
      await again.ready()
      await again.flush()
      const after = await sql.query<{ count: string }>('select count(*)::text as count from memory_record')
      expect(after.rows[0]?.count).toBe(before)
    } finally {
      await close()
    }
  })

  it('evicts whole turns from the table when the cap is passed', async () => {
    const { sql, close } = await engine()
    try {
      const store = createPostgresStore({ sql, maxRecords: 3, identifierWeight: 1.5 })
      await store.ready()
      for (const turn of [1, 2, 3, 4]) {
        for (const record of recordsOf(turn, `端口 ${String(turn)} 的说明要多长才算一条`, '好的。')) {
          store.record(record)
        }
      }
      await store.flush()
      const { rows } = await sql.query<{ turn: number }>(
        'select distinct turn from memory_record order by turn',
      )
      // One record per turn here, so the cap keeps the newest three and turn 1
      // is gone. The invariant that matters is the next line: the table holds
      // exactly what the mirror decided to keep.
      expect(rows.map(row => row.turn)).toEqual([2, 3, 4])
      const total = await sql.query<{ count: string }>(
        'select count(*)::text as count from memory_record',
      )
      expect(Number(total.rows[0]?.count)).toBe(store.count())
    } finally {
      await close()
    }
  })

  it('agrees with the in-memory store on every hit', async () => {
    // The drift guard: both implementations share the scoring and the eviction
    // rule, so any difference here is a real one.
    const { sql, close } = await engine()
    try {
      const durable = createPostgresStore({ sql, maxRecords: 50, identifierWeight: 1.5 })
      await durable.ready()
      const memory = createInMemoryStore({ maxRecords: 50, identifierWeight: 1.5 })
      const turns: readonly (readonly [string, string])[] = [
        ['部署端口固定为 8080，改动要走变更流程。', '好的。'],
        ['数据库用 PostgreSQL 18，向量检索另说。', '已记录。'],
        ['许可证是 Apache-2.0。', '好的。'],
        ['日志路径 /var/log/app，按天切分。', '好的。'],
      ]
      turns.forEach(([prompt, reply], index) => {
        for (const record of recordsOf(index + 1, prompt, reply)) {
          durable.record(record)
          memory.record(record)
        }
      })
      await durable.flush()

      for (const text of ['端口是多少', '数据库版本', '许可证', '日志路径在哪', 'Kubernetes ingress']) {
        const query = { text, limit: 6, minScore: 0.4 }
        expect(durable.recall(query).map(hit => [hit.record.id, hit.score]))
          .toEqual(memory.recall(query).map(hit => [hit.record.id, hit.score]))
      }
    } finally {
      await close()
    }
  })

  it('agrees with the in-memory fact store on the current value', async () => {
    const { sql, close } = await engine()
    try {
      const durable = createPostgresFactStore({ sql, maxFacts: 100 })
      await durable.ready()
      const memory = createFactStore({ maxFacts: 100 })
      const extractions: readonly string[] = [
        '前端端口 5173，后端端口 8080',
        '端口改成 9090 了，8080 不用了',
        '数据库版本升到 v2.3.1',
        '负责人是 张三',
      ]
      extractions.forEach((text, index) => {
        const turn = index + 1
        const extraction = extractFacts(text)
        durable.apply('session', turn, turn * 1000, extraction)
        memory.apply('session', turn, turn * 1000, extraction)
      })
      await durable.flush()

      for (const text of ['端口是多少', '前端端口是多少', '之前端口是多少', '数据库版本', '负责人是谁']) {
        expect(durable.query('session', text).active.map(fact => fact.object))
          .toEqual(memory.query('session', text).active.map(fact => fact.object))
        expect(durable.query('session', text).superseded.map(fact => fact.object))
          .toEqual(memory.query('session', text).superseded.map(fact => fact.object))
      }
    } finally {
      await close()
    }
  })
})
