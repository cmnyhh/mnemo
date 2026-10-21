import { describe, expect, it } from 'vitest'
import { createInMemoryStore } from '../src/store.ts'
import type { MemoryRecord } from '../src/types.ts'

/**
 * Generalisation check, run on material that took no part in calibration.
 *
 * `calibration.spec.ts` measures the operating point on the same 21 queries the
 * floor was chosen with, so it cannot tell a real improvement from a fitted one.
 * This file exists because that distinction was tested and the answer was
 * uncomfortable: on this unseen corpus the scorer recalls 6 of 8, not the 14 of
 * 14 the calibration set reports.
 *
 * That 75% is the number to quote for "how well does it generalise", and it is
 * the number to beat. When the floor or the tokeniser changes, this moves.
 */

const corpus: ReadonlyArray<readonly [string, string]> = [
  ['h1', '用户: 前端构建用 Vite 5，不要换 Webpack。\n助手: 好。'],
  ['h2', '用户: 灰度发布先放百分之一的流量。\n助手: 已记录。'],
  ['h3', '用户: 日志保留七天，超过的自动归档到对象存储。\n助手: 明白。'],
  ['h4', '用户: 这个接口的限流是每分钟 600 次。\n助手: 收到。'],
  ['h5', '用户: 线上告警只发到运维值班群，不要全员群发。\n助手: 好。'],
  ['h6', '用户: 图片统一裁剪成 1200x630。\n助手: 已记录。'],
  ['h7', '用户: 报错文案不许出现堆栈信息。\n助手: 明白。'],
  ['h8', '用户: 每周三下午做数据对账。\n助手: 收到。'],
]

/** `null` means the query must produce no block at all. */
const queries: ReadonlyArray<readonly [string, string | null]> = [
  ['前端构建工具用的哪个', 'h1'],
  ['灰度放多少流量', 'h2'],
  ['日志保留多久', 'h3'],
  ['接口限流多少', 'h4'],
  ['告警发到哪个群', 'h5'],
  ['图片尺寸是多少', 'h6'],
  ['报错能不能带堆栈', 'h7'],
  ['数据对账哪天做', 'h8'],
  ['移动端怎么适配', null],
  ['数据库连接池设多大', null],
  ['favicon 用什么格式', null],
]

function seed(): ReturnType<typeof createInMemoryStore> {
  const store = createInMemoryStore({ maxRecords: 2000, identifierWeight: 1.5 })
  let clock = 1
  for (const [id, text] of corpus) {
    const record: MemoryRecord = { id, sessionId: 's', kind: 'turn', text, createdAt: clock, turn: clock }
    store.record(record)
    clock += 1
  }
  return store
}

describe('recall on unseen material', () => {
  it('recalls 6 of the 8 answerable queries', () => {
    const store = seed()
    const answerable = queries.filter(([, want]) => want !== null)
    const recalled = answerable.filter(
      ([query, want]) => store.recall({ text: query, limit: 6, minScore: 0.4 }).some((hit) => hit.record.id === want),
    )
    // 75%, against 100% on the calibration set. The gap is the honest measure of
    // overfitting; asserting the exact count makes any further regression visible
    // instead of hiding behind a rounded percentage.
    expect(recalled.length).toBe(6)
  })

  it('abstains on all three unanswerable queries', () => {
    const store = seed()
    const unanswerable = queries.filter(([, want]) => want === null)
    const injected = unanswerable.filter(([query]) => store.recall({ text: query, limit: 6, minScore: 0.4 }).length > 0)
    expect(injected.map(([query]) => query)).toEqual([])
  })

  it('misses two short queries for the same reason: one shared bigram is diluted by six', () => {
    const store = seed()
    // 图片尺寸|是|多少 tokenises to 6 bigrams and the record answers with 图片
    // only; 报错|能不能|带堆栈 shares 报错 and 堆栈 out of 7. Both land at 0.286,
    // under the floor. Longer queries in the same corpus clear it because a
    // larger share of their bigrams can be matched.
    for (const query of ['图片尺寸是多少', '报错能不能带堆栈']) {
      const top = store.recall({ text: query, limit: 1, minScore: 0 })[0]
      expect(top?.score).toBeLessThan(0.4)
    }
  })
})

describe('a record that mentions the subject but does not answer the question', () => {
  it('is injected — the gap the floor cannot close', () => {
    const store = createInMemoryStore({ maxRecords: 10, identifierWeight: 1.5 })
    store.record({
      id: 'vector',
      sessionId: 's',
      kind: 'turn',
      text: '用户: 向量后端默认用 Qdrant，pgvector 只作轻量模式。\n助手: 明白。',
      createdAt: 1,
      turn: 1,
    })

    // `pgvector` is a rare identifier, so one match is enough to clear the floor
    // at 0.448 — but the record never states a dimension. Coverage measures
    // subject overlap, not whether an answer is present, so this is a wrong
    // injection that looks like a right one. Pinned as a known gap: it should
    // flip to an abstention once M1 reranking scores answer-bearing spans.
    const hits = store.recall({ text: 'pgvector 支持多少维', limit: 1, minScore: 0.4 })
    expect(hits[0]?.record.id).toBe('vector')
  })
})
