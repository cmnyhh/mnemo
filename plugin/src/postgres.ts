/**
 * Durable stores over the {@link Sql} port.
 *
 * Both are **read-through**: recall and fact resolution stay synchronous and
 * answer from an in-memory mirror, while writes go to a queue that `flush()`
 * commits. The alternative — making the `MemoryStore` and `FactStore` ports
 * async — would change every call site and every test to buy a guarantee this
 * layer does not need, because these writes are memory, not a ledger: losing the
 * last unflushed batch costs a recall, not correctness.
 *
 * The mirror is also what keeps the two stores honest. Everything that decides
 * *what* changes is shared with the in-memory implementation
 * ({@link planFactApplication}, {@link resolveFacts}, the eviction callback), so
 * a durable store cannot drift into a different override rule than the one the
 * tests pin.
 *
 * Row identity is generated here, not by the database. A fact closed on a later
 * turn is closed by id, and an id the mirror invented but never committed still
 * has to be closed eventually, so the adapter that owns the mirror owns the id.
 * @module @deepseek-ai/dsh-memory-core/postgres
 */

import { randomUUID } from 'node:crypto'

import type { Fact, FactExtraction, FactStatement, FactStore, FactView } from './facts.ts'
import { planFactApplication, resolveFacts } from './facts.ts'
import { createInMemoryStore } from './store.ts'
import type { InMemoryStore, InMemoryStoreOptions } from './store.ts'
import type { Sql } from './sql.ts'
import type { MemoryHit, MemoryKind, MemoryRecord, MemoryStore, RecallQuery, RecordedFact } from './types.ts'

/** Rows per `insert`; keeps one statement inside the driver's parameter cap. */
const INSERT_BATCH = 200

/** A record row as the schema returns it. */
interface RecordRow {
  readonly id: string
  readonly session_id: string
  readonly kind: string
  readonly turn: number
  readonly body: string
  readonly context: string | null
  readonly created_at: number | string | bigint
  readonly facts: readonly RecordedFact[] | string | null
}

/** A fact row as the schema returns it. */
interface FactRow {
  readonly id: string
  readonly session_id: string
  readonly predicate: string
  readonly subject: string
  readonly object: string
  readonly valid_from: number | string
  readonly valid_to: number | string | null
  readonly recorded_at: number | string | bigint
}

/** A statement that has an id but has not reached the table yet. */
interface PendingFact extends FactStatement {
  readonly id: string
}

/** Options for {@link createPostgresStore}. */
export interface PostgresStoreOptions extends InMemoryStoreOptions {
  /** Execution port. */
  readonly sql: Sql
}

/** A {@link MemoryStore} whose records survive the process. */
export interface DurableMemoryStore extends MemoryStore {
  /** Load retained rows into the mirror; call once, before the plugin starts. */
  ready(): Promise<void>
  /** Commit queued inserts and deletes. */
  flush(): Promise<void>
  /** Records currently in the mirror. */
  count(): number
}

/**
 * Convert a bigint column that a driver may hand back as a string.
 * @param value - column value.
 * @returns the same number.
 */
function toNumber(value: number | string | bigint): number {
  return typeof value === 'number' ? value : Number(value)
}

/**
 * Parse a jsonb column a driver may hand back already decoded.
 * @param value - column value.
 * @returns the array, or an empty one.
 */
function toFacts(value: readonly RecordedFact[] | string | null): readonly RecordedFact[] {
  if (value === null) return []
  if (typeof value !== 'string') return value
  const parsed: unknown = JSON.parse(value)
  return Array.isArray(parsed) ? parsed as RecordedFact[] : []
}

/**
 * Map a row to the record the store holds.
 * @param row - row from `memory_record`.
 * @returns the record.
 */
function recordOf(row: RecordRow): MemoryRecord {
  const facts = toFacts(row.facts)
  return {
    id: row.id,
    sessionId: row.session_id,
    kind: row.kind as MemoryKind,
    text: row.body,
    ...(row.context === null ? {} : { context: row.context }),
    createdAt: toNumber(row.created_at),
    turn: row.turn,
    ...(facts.length === 0 ? {} : { facts }),
  }
}

/**
 * Create a store whose records are mirrored in memory and committed to Postgres.
 * @param options - retention cap, term weights, and the execution port.
 * @returns a store that must be `ready()` before use and `flush()`ed to persist.
 */
export function createPostgresStore(options: PostgresStoreOptions): DurableMemoryStore {
  const pending: MemoryRecord[] = []
  const removed: string[] = []
  /** While loading, the mirror must not treat hydrated rows as new writes. */
  let hydrating = true

  const inner: InMemoryStore = createInMemoryStore({
    maxRecords: options.maxRecords,
    identifierWeight: options.identifierWeight,
    onEvict: records => {
      if (hydrating) return
      for (const record of records) removed.push(record.id)
    },
  })

  return {
    async ready() {
      // Keep whole turns: a `limit` on rows can cut one in half, and a record
      // without its question is worse than a missing turn.
      const { rows } = await options.sql.query<RecordRow>(
        `with keep as (select turn from memory_record order by seq desc limit $1)
         select id, session_id, kind, turn, body, context, created_at, facts
           from memory_record
          where turn >= (select min(turn) from keep)
          order by seq asc`,
        [options.maxRecords],
      )
      for (const row of rows) inner.record(recordOf(row))
      hydrating = false
    },

    record(record) {
      inner.record(record)
      if (!hydrating) pending.push(record)
    },

    recall(query: RecallQuery): readonly MemoryHit[] {
      return inner.recall(query)
    },

    async flush() {
      while (pending.length > 0) {
        const batch = pending.splice(0, INSERT_BATCH)
        const values: unknown[] = []
        const tuples = batch.map((record, index) => {
          const base = index * 8
          values.push(
            record.id,
            record.sessionId,
            record.kind,
            record.turn,
            record.text,
            record.context ?? null,
            record.createdAt,
            JSON.stringify(record.facts ?? []),
          )
          return `($${String(base + 1)}, $${String(base + 2)}, $${String(base + 3)}, `
            + `$${String(base + 4)}, $${String(base + 5)}, $${String(base + 6)}, `
            + `$${String(base + 7)}, $${String(base + 8)}::jsonb)`
        })
        // `do nothing` keeps the flush idempotent: a crash between commit and
        // queue clear replays a batch, and replay must not fail.
        await options.sql.query(
          `insert into memory_record
             (id, session_id, kind, turn, body, context, created_at, facts)
           values ${tuples.join(', ')}
           on conflict (id) do nothing`,
          values,
        )
      }

      if (removed.length > 0) {
        const ids = removed.splice(0)
        await options.sql.query('delete from memory_record where id = any($1::text[])', [ids])
      }
    },

    count(): number {
      return inner.count()
    },
  }
}

/** Options for {@link createPostgresFactStore}. */
export interface PostgresFactOptions {
  /** Execution port. */
  readonly sql: Sql
  /** Cap on retained statements, current and retired. */
  readonly maxFacts: number
}

/** A {@link FactStore} whose statements survive the process. */
export interface DurableFactStore extends FactStore {
  /** Load retained statements into the mirror; call once, before the plugin starts. */
  ready(): Promise<void>
  /** Commit queued closes, inserts, and deletes. */
  flush(): Promise<void>
}

/**
 * Create a store whose facts are mirrored in memory and committed to Postgres.
 *
 * Resolution runs over the mirror rather than over SQL because the rule is not
 * a filter: a subjectless statement supersedes every subject, and a query with
 * no subject falls back to the default row. Expressing that in SQL is possible
 * and unreadable; sharing {@link resolveFacts} with the in-memory store is what
 * guarantees the two answer identically.
 * @param options - cap and the execution port.
 * @returns a store that must be `ready()` before use and `flush()`ed to persist.
 */
export function createPostgresFactStore(options: PostgresFactOptions): DurableFactStore {
  const facts: Fact[] = []
  /** Retired facts per turn, so one turn costs one update. */
  const closes = new Map<number, string[]>()
  const retitles: { id: string; subject: string }[] = []
  const inserts: PendingFact[] = []
  const removed: string[] = []
  let hydrating = true

  const trim = (): void => {
    while (facts.length > options.maxFacts) {
      const retired = facts.findIndex(fact => fact.validTo !== undefined)
      const index = retired >= 0 ? retired : 0
      const gone = facts.splice(index, 1)[0]
      if (gone === undefined) break
      if (hydrating) continue
      removed.push(gone.id)
      // A statement queued but not yet committed must not be committed after it
      // was dropped, or the flush would resurrect a row the mirror no longer has.
      const queued = inserts.findIndex(row => row.id === gone.id)
      if (queued >= 0) inserts.splice(queued, 1)
    }
  }

  return {
    async ready() {
      const { rows } = await options.sql.query<FactRow>(
        `select id, session_id, predicate, subject, object, valid_from, valid_to, recorded_at
           from memory_fact order by recorded_at asc, id asc`,
      )
      for (const row of rows) {
        facts.push({
          id: row.id,
          sessionId: row.session_id,
          predicate: row.predicate as Fact['predicate'],
          subject: row.subject,
          object: row.object,
          validFrom: toNumber(row.valid_from),
          ...(row.valid_to === null ? {} : { validTo: toNumber(row.valid_to) }),
          recordedAt: toNumber(row.recorded_at),
        })
      }
      hydrating = false
    },

    apply(sessionId, turn, at, extraction: FactExtraction) {
      const plan = planFactApplication(facts, sessionId, turn, at, extraction)
      const closing = new Set(plan.close)
      for (let index = 0; index < facts.length; index += 1) {
        const fact = facts[index]
        if (fact === undefined || !closing.has(fact.id)) continue
        facts[index] = { ...fact, validTo: turn }
      }
      for (const entry of plan.retitle) {
        const index = facts.findIndex(fact => fact.id === entry.id)
        const held = facts[index]
        if (held !== undefined) facts[index] = { ...held, subject: entry.subject }
        if (!hydrating) retitles.push(entry)
      }
      for (const row of plan.insert) {
        const id = randomUUID()
        facts.push({ id, ...row })
        if (!hydrating) inserts.push({ id, ...row })
      }

      if (!hydrating) {
        for (const id of plan.close) {
          // Not in the table yet: closing it means editing the queued row, not
          // running an update that matches nothing.
          const queued = inserts.findIndex(row => row.id === id)
          if (queued >= 0) {
            const row = inserts[queued]
            if (row !== undefined) inserts[queued] = { ...row, validTo: turn }
            continue
          }
          const group = closes.get(turn)
          if (group === undefined) closes.set(turn, [id])
          else group.push(id)
        }
      }

      trim()
    },

    query(sessionId: string, text: string): FactView {
      return resolveFacts(facts, sessionId, text)
    },

    count(): number {
      return facts.length
    },

    async flush() {
      for (const [turn, ids] of closes) {
        await options.sql.query(
          'update memory_fact set valid_to = $1 where id = any($2::text[]) and valid_to is null',
          [turn, ids],
        )
      }
      closes.clear()

      for (const entry of retitles) {
        await options.sql.query(
          'update memory_fact set subject = $1 where id = $2',
          [entry.subject, entry.id],
        )
      }
      retitles.length = 0

      while (inserts.length > 0) {
        const batch = inserts.splice(0, INSERT_BATCH)
        const values: unknown[] = []
        const tuples = batch.map((row, index) => {
          const base = index * 8
          values.push(
            row.id,
            row.sessionId,
            row.predicate,
            row.subject,
            row.object,
            row.validFrom,
            row.validTo ?? null,
            row.recordedAt,
          )
          return `($${String(base + 1)}, $${String(base + 2)}, $${String(base + 3)}, `
            + `$${String(base + 4)}, $${String(base + 5)}, $${String(base + 6)}, `
            + `$${String(base + 7)}, $${String(base + 8)})`
        })
        await options.sql.query(
          `insert into memory_fact
             (id, session_id, predicate, subject, object, valid_from, valid_to, recorded_at)
           values ${tuples.join(', ')}
           on conflict (id) do nothing`,
          values,
        )
      }

      if (removed.length > 0) {
        const ids = removed.splice(0)
        await options.sql.query('delete from memory_fact where id = any($1::text[])', [ids])
      }
    },
  }
}
