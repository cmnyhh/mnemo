import { describe, expect, it } from 'vitest'
import { renderHitList, renderRecallBlock } from '../src/render.ts'
import { createInMemoryStore } from '../src/store.ts'
import type { MemoryHit } from '../src/types.ts'

/**
 * The excerpt has to contain the evidence.
 *
 * Scoring reads the whole record; rendering quotes 240 characters of it. If the
 * two disagree, a record can rank first on a phrase the model never sees — the
 * plugin pays the ranking cost and delivers none of the answer. These cases pin
 * the window to the matched terms rather than to character zero.
 */

const PADDING = '这里是无关的填充内容，'

function hitOf(text: string, query = '端口是多少'): MemoryHit {
  const store = createInMemoryStore({ maxRecords: 10, identifierWeight: 1.5 })
  store.record({ id: 'long', sessionId: 's', kind: 'turn', text, createdAt: 1, turn: 1 })
  const [hit] = store.recall({ text: query, limit: 1, minScore: 0.4 })
  if (hit === undefined) throw new Error('expected a hit for the fixture')
  return hit
}

describe('excerpt windowing', () => {
  it('keeps an answer that sits in the middle of a long record', () => {
    // Head-and-tail truncation cannot reach this one: the answer is neither at
    // the start nor at the end, so only a window opened at the matched term
    // keeps it. That is the difference this file exists to pin.
    const text = `用户: ${PADDING.repeat(20)}\n最后一句才是答案：端口是 8080。\n${PADDING.repeat(20)}\n助手: 好。`
    const block = renderRecallBlock([hitOf(text)], { budgetChars: 1200, maxLineChars: 240 })
    expect(block).toContain('8080')
  })

  it('keeps both ends when the store cannot say what matched', () => {
    const text = `用户: ${PADDING.repeat(40)}\n答案：端口是 8080，这一句也必须保住。\n助手: 好。`
    const degraded: MemoryHit = { record: hitOf(text).record, score: 0.9 }
    const block = renderRecallBlock([degraded], { budgetChars: 1200, maxLineChars: 240 })
    expect(block).toContain('这一句也必须保住')
  })

  it('never exceeds the per-line budget', () => {
    const text = `用户: ${PADDING.repeat(40)}\n答案：端口是 8080。\n助手: 好。`
    const block = renderRecallBlock([hitOf(text)], { budgetChars: 4000, maxLineChars: 120 })
    const line = block?.split('\n')[1] ?? ''
    // `- ` prefix plus at most maxLineChars of text.
    expect(line.length).toBeLessThanOrEqual(2 + 120)
  })

  it('applies the same window to the tool surface', () => {
    const text = `用户: ${PADDING.repeat(40)}\n答案：端口是 8080。\n助手: 好。`
    expect(renderHitList([hitOf(text)])).toContain('8080')
  })
})
