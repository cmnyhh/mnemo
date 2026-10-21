import { describe, expect, it } from 'vitest'
import { createInMemoryStore } from '../src/store.ts'

/**
 * Retention is by turn, not by record.
 *
 * One turn is several records now, so evicting a single record at the cap would
 * cut the turn in half: the answer survives while the question that gave it
 * meaning is gone. That is worse than dropping the turn, because the model reads
 * the leftover as a fact with no scope.
 */

/** Record one turn's segments, all sharing a turn number. */
function recordTurn(
  store: ReturnType<typeof createInMemoryStore>,
  number: number,
  texts: readonly string[],
): void {
  for (const [index, text] of texts.entries()) {
    store.record({
      id: `t${number}-${index}`,
      sessionId: 's',
      kind: 'turn',
      text,
      createdAt: number * 10 + index,
      turn: number,
    })
  }
}

describe('retention', () => {
  it('evicts a whole turn, never half of one', () => {
    const store = createInMemoryStore({ maxRecords: 3, identifierWeight: 1.5 })
    recordTurn(store, 1, ['端口是 8080', '端口备用 9090', '端口备注：老机房'])
    recordTurn(store, 2, ['备份每天三点'])

    expect(store.recall({ text: '端口', limit: 10, minScore: 0 })).toHaveLength(0)
    expect(store.recall({ text: '备份', limit: 10, minScore: 0 })).toHaveLength(1)
  })

  it('keeps the only turn when the cap is smaller than that turn', () => {
    // Otherwise a single long turn would evict itself and the store would go
    // empty while holding exactly the text it was asked to keep.
    const store = createInMemoryStore({ maxRecords: 1, identifierWeight: 1.5 })
    recordTurn(store, 1, ['端口是 8080', '端口备用 9090'])

    expect(store.recall({ text: '端口', limit: 10, minScore: 0 })).toHaveLength(2)
  })
})
