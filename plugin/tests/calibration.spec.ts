import { describe, expect, it } from 'vitest'
import { createInMemoryStore } from '../src/store.ts'
import type { MemoryRecord } from '../src/types.ts'

/**
 * Calibration lock for the lexical scorer.
 *
 * The README claims a measured operating point, and a claim in prose decays: the
 * tokeniser changes, the claim stays. This file makes the claim executable. It is
 * a 21-query sample, not a benchmark — the point is that moving the scorer
 * *changes this number*, so a regression cannot pass unnoticed.
 *
 * Expected behaviour when the tokeniser changes (jieba in M1): re-measure, then
 * update both this table and the README together.
 */

const corpus: ReadonlyArray<readonly [string, string]> = [
  ['r1', '用户: 记忆系统决定用 PostgreSQL 18，不用 MySQL。\n助手: 已记录。'],
  ['r2', '用户: 部署端口固定为 8080，生产环境不要改。\n助手: 好。'],
  ['r3', '用户: 向量后端默认用 Qdrant，pgvector 只作轻量模式。\n助手: 明白。'],
  ['r4', '用户: BM25 用 pg_textsearch，许可证是 PostgreSQL License。\n助手: 记录。'],
  ['r5', '用户: 中文分词在数据库里用 pg_jieba，text_config 设为 jiebacfg。\n助手: 已记下。'],
  ['r6', '用户: 召回延迟预算 P95 不超过 400 毫秒。\n助手: 好。'],
  ['r7', '用户: 编码统一用 UTF-8。\n助手: 已记录。'],
  ['r8', '用户: 客户编号 AC-2048，对接人要找张工。\n助手: 收到。'],
  ['r9', '用户: 数据库备份每天凌晨三点跑，保留三十天。\n助手: 好。'],
  ['r10', '用户: 所有时间戳都用 UTC 存储。\n助手: 明白。'],
  ['r11', '用户: 这个项目用 pnpm 管理，不要混 npm。\n助手: 好。'],
  ['r12', '用户: 测试只跑 packages/memory 下的用例。\n助手: 收到。'],
]

/** `null` means the query must produce no block at all. */
const queries: ReadonlyArray<readonly [string, string | null]> = [
  ['部署端口是多少？', 'r2'],
  ['向量后端用哪个', 'r3'],
  ['pg_textsearch 的许可证是什么', 'r4'],
  ['中文分词怎么配', 'r5'],
  ['召回延迟预算是多少', 'r6'],
  ['编码用什么', 'r7'],
  ['AC-2048 找谁对接', 'r8'],
  ['备份保留几天', 'r9'],
  ['时间戳用什么时区', 'r10'],
  ['包管理用 pnpm 还是 npm', 'r11'],
  ['测试跑哪些用例', 'r12'],
  ['PostgreSQL 版本是多少', 'r1'],
  ['生产环境端口能改吗', 'r2'],
  ['P95 是多少毫秒', 'r6'],
  ['今天天气怎么样', null],
  ['如何重装打印机驱动', null],
  ['帮我订明天的会议室', null],
  ['数据库选型是什么', null],
  ['Kubernetes 的 ingress 怎么配', null],
  ['Redis 集群怎么扩容', null],
  ['这个多少钱', null],
]

/** The plugin's default floor; the table above is only meaningful against it. */
const FLOOR = 0.4

function build(): ReturnType<typeof createInMemoryStore> {
  const store = createInMemoryStore({ maxRecords: 2000, identifierWeight: 1.5 })
  let clock = 1
  for (const [id, text] of corpus) {
    const record: MemoryRecord = { id, sessionId: 's', kind: 'turn', text, createdAt: clock, turn: clock }
    store.record(record)
    clock += 1
  }
  return store
}

describe('calibration', () => {
  it('recalls every answerable query at the default floor', () => {
    const store = build()
    const missed = queries
      .filter(([, want]) => want !== null)
      .filter(([query, want]) => !store.recall({ text: query, limit: 6, minScore: FLOOR }).some((hit) => hit.record.id === want))
      .map(([query]) => query)
    expect(missed).toEqual([])
  })

  it('injects nothing for a query memory cannot answer', () => {
    const store = build()
    const leaked = queries
      .filter(([, want]) => want === null)
      .flatMap(([query]) => store.recall({ text: query, limit: 6, minScore: FLOOR }).map((hit) => `${query} -> ${hit.record.id}`))
    expect(leaked).toEqual([])
  })

  it('keeps the floor between the two closest cases', () => {
    // The floor sits in a 0.02 gap: "备份保留几天" scores 0.400 above it and
    // "数据库选型是什么" scores 0.379 below. That gap is the honest measure of
    // how much room bigram tokenisation leaves — it is why the floor moves last
    // and why word segmentation, not more weights, is the next step.
    const store = build()
    const positive = store.recall({ text: '备份保留几天', limit: 1, minScore: 0 })[0]
    const negative = store.recall({ text: '数据库选型是什么', limit: 1, minScore: 0 })[0]
    expect(positive?.score).toBeGreaterThanOrEqual(FLOOR)
    expect(negative?.score).toBeLessThan(FLOOR)
  })
})
