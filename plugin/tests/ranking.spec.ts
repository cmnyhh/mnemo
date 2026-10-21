import { describe, expect, it } from 'vitest'
import { createInMemoryStore } from '../src/store.ts'
import { termWeight, tokenize } from '../src/text.ts'
import type { MemoryHit, MemoryRecord } from '../src/types.ts'

/**
 * Scoring tests for the lexical store.
 *
 * They pin the properties that make the score worth having — a question's
 * grammar does not dilute a real hit, an identifier outranks prose, a term every
 * record shares decides nothing — rather than the exact number any one query
 * produces. The exact numbers are calibrated against a corpus; the properties
 * are not.
 */

interface Seed {
  readonly id: string
  readonly text: string
}

function seed(records: readonly Seed[], maxRecords = 100): ReturnType<typeof createInMemoryStore> {
  const store = createInMemoryStore({ maxRecords, identifierWeight: 1.5 })
  let clock = 1
  for (const item of records) {
    const record: MemoryRecord = {
      id: item.id,
      sessionId: 's',
      kind: 'turn',
      text: item.text,
      createdAt: clock,
      turn: clock,
    }
    store.record(record)
    clock += 1
  }
  return store
}

function ids(store: ReturnType<typeof createInMemoryStore>, query: string, minScore = 0.4): string[] {
  return store.recall({ text: query, limit: 6, minScore }).map((hit) => hit.record.id)
}

/** The best hit with no floor, or a loud failure — a silent empty array would hide the case. */
function topHit(store: ReturnType<typeof createInMemoryStore>, query: string): MemoryHit {
  const [hit] = store.recall({ text: query, limit: 1, minScore: 0 })
  if (hit === undefined) throw new Error(`expected at least one hit for: ${query}`)
  return hit
}

describe('term weighting', () => {
  it('gives grammar almost no weight and content full weight', () => {
    expect(termWeight('什么', 1.5)).toBeLessThan(0.1)
    expect(termWeight('多少', 1.5)).toBeLessThan(0.1)
    expect(termWeight('端口', 1.5)).toBe(1)
    // One function character is not enough to call the bigram grammar: "型是"
    // is a boundary artefact, not the word "是".
    expect(termWeight('型是', 1.5)).toBeGreaterThan(0.1)
    expect(termWeight('型是', 1.5)).toBeLessThan(1)
  })

  it('weights an identifier above a Chinese bigram', () => {
    expect(termWeight('postgresql', 1.5)).toBe(1.5)
    expect(termWeight('2048', 1.5)).toBe(1.5)
  })

  it('folds full-width input onto the ASCII spelling', () => {
    expect(tokenize('ＰｏｓｔｇｒｅＳＱＬ')).toEqual(tokenize('PostgreSQL'))
  })
})

describe('recall scoring', () => {
  it('is not diluted by the grammar of a question', () => {
    // Under unweighted coverage this is 3/6 = 0.50: "口是", "是多", "多少" pay
    // for themselves out of the budget. The hit is a complete match on the part
    // of the query that means anything.
    const store = seed([{ id: 'port', text: '用户: 部署端口固定为 8080。\n助手: 好。' }])
    const top = topHit(store, '部署端口是多少？')
    expect(top.record.id).toBe('port')
    // 0.86 on a single-record corpus, 0.90 once the corpus has a dozen: the floor
    // has to sit below that band, which is why it is 0.4 and not 0.6.
    expect(top.score).toBeGreaterThan(0.75)
  })

  it('ranks the record that carries the identifier first', () => {
    // Deliberately balanced: each record shares exactly one query term with the
    // query, so the only thing that can order them is the identifier multiplier.
    // The prose record is newer, so at equal weight it would win the tie — which
    // is what makes this assertion about the weight and not about the tie-break.
    const store = seed([
      { id: 'identifier', text: '用户: postgresql 是选定的引擎。\n助手: 好。' },
      { id: 'prose', text: '用户: 主库存量盘点完成。\n助手: 好。' },
    ])
    expect(ids(store, 'postgresql 主库')[0]).toBe('identifier')
  })

  it('does not let a term every record shares decide the ranking', () => {
    // "项目" is in both records, so it says nothing; the answer has to come from
    // the term only one of them has.
    const store = seed([
      { id: 'budget', text: '用户: 这个项目的召回延迟预算是 P95 400 毫秒。\n助手: 好。' },
      { id: 'style', text: '用户: 这个项目的配色偏冷。\n助手: 好。' },
    ])
    expect(ids(store, '项目的延迟预算是多少')[0]).toBe('budget')
  })

  it('abstains on a query made only of grammar', () => {
    const store = seed([{ id: 'port', text: '用户: 部署端口固定为 8080。\n助手: 好。' }])
    expect(ids(store, '怎么样')).toEqual([])
    expect(ids(store, '是什么呢')).toEqual([])
  })

  it('drops the document frequency of an evicted record', () => {
    // maxRecords=1: recording "c" evicts "a", so terms unique to "a" must leave
    // the index. A stale count would keep making them look common.
    const store = seed(
      [
        { id: 'a', text: '用户: 配色用蓝灰。\n助手: 好。' },
        { id: 'c', text: '用户: 部署端口固定为 8080。\n助手: 好。' },
      ],
      1,
    )
    expect(ids(store, '配色偏冷')).toEqual([])
    expect(ids(store, '部署端口是多少')).toEqual(['c'])
  })
})
