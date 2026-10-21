/**
 * M0 in-memory memory store.
 *
 * It is a complete {@link MemoryStore} — not a stub — so the plugin's capture,
 * recall, and rendering paths can be exercised without a database. It is also
 * the reference for what the Postgres adapter must reproduce: an explicit
 * retention cap, and a recall that abstains rather than returning low-coverage
 * noise.
 *
 * Scoring is weighted term coverage, shaped like the M1 BM25 index: each query
 * term contributes `kind × idf` to a budget, a record scores the share of that
 * budget it contains, and the result stays in `[0, 1]` so one floor means the
 * same thing for a three-word query and a ten-word one.
 * @module @deepseek-ai/dsh-memory-core/store
 */

import type { MemoryHit, MemoryRecord, MemoryStore, RecallQuery } from './types.ts'
import { inverseDocumentFrequency, termWeight, tokenize } from './text.ts'

/**
 * A retained record plus its postings.
 *
 * Terms are tokenised once at write time, not once per recall: a recall then
 * costs `records × queryTerms` instead of `records × recordLength`, which is
 * what keeps a full scan affordable at the retention cap.
 */
interface IndexedRecord {
  /** The record as the caller handed it over. */
  readonly record: MemoryRecord
  /** Term counts: presence decides the score, counts are kept for the M1 tf. */
  readonly terms: Map<string, number>
}

/** Construction options for {@link createInMemoryStore}. */
export interface InMemoryStoreOptions {
  /** Hard cap on retained records; the oldest record is evicted past it. */
  readonly maxRecords: number
  /**
   * Multiplier on Latin/digit terms (`postgresql`, `8080`, `ac`).
   *
   * An identifier is the strongest evidence a short query carries: it is chosen
   * deliberately and it is rare, so a hit on it should outweigh a Chinese bigram
   * that adjacency produced by accident.
   */
  readonly identifierWeight: number
  /**
   * Called with every record the cap evicted, in eviction order.
   *
   * A durable adapter has to remove the same rows from storage. Handing them
   * over here beats re-deriving "which turn is oldest" in SQL: the in-memory
   * rule is turn-atomic and refuses to empty a one-turn store, and a second
   * implementation of that would be a place for the two to disagree.
   */
  readonly onEvict?: (records: readonly MemoryRecord[]) => void
}

/**
 * The process-local store, with the one extra reading a durable adapter needs.
 *
 * `count` is not on {@link MemoryStore}: recall and capture never ask how much
 * is retained. It exists so a hydrated adapter can be checked against the rows
 * it loaded without reaching into the closure.
 */
export interface InMemoryStore extends MemoryStore {
  /** Records currently retained. */
  count(): number
}

/**
 * Create the process-local memory store.
 *
 * Records are retained in insertion order so eviction needs no index. Recall is
 * a full scan: the cap bounds it, and an exact scan is the honest baseline the
 * M1 hybrid index has to beat on both recall and latency.
 * @param options - retention cap and term weights.
 * @returns a store that lives as long as its plugin fiber.
 */
export function createInMemoryStore(options: InMemoryStoreOptions): InMemoryStore {
  const entries: IndexedRecord[] = []
  /** Document frequency per term; maintained on write and reversed on eviction. */
  const documentFrequency = new Map<string, number>()

  return {
    record(record) {
      const terms = tokenize(record.text)
      for (const term of terms.keys()) {
        documentFrequency.set(term, (documentFrequency.get(term) ?? 0) + 1)
      }
      entries.push({ record, terms })
      while (entries.length > options.maxRecords) {
        const oldest = entries[0]
        const newest = entries[entries.length - 1]
        if (oldest === undefined || newest === undefined) break
        // A turn is atomic. One turn is now several records, so evicting a single
        // record would cut a turn in half and leave an answer whose question is
        // gone — worse than dropping the turn. Skip eviction when the whole store
        // is one turn, or the cap would empty it.
        if (newest.record.turn === oldest.record.turn) break
        const evicted: MemoryRecord[] = []
        while (entries.length > 0 && entries[0]?.record.turn === oldest.record.turn) {
          const entry = entries.shift()
          if (entry === undefined) break
          evicted.push(entry.record)
          for (const term of entry.terms.keys()) {
            const remaining = (documentFrequency.get(term) ?? 0) - 1
            if (remaining <= 0) documentFrequency.delete(term)
            else documentFrequency.set(term, remaining)
          }
        }
        if (evicted.length > 0) options.onEvict?.(evicted)
      }
    },

    recall(query: RecallQuery): readonly MemoryHit[] {
      const queryTerms = tokenize(query.text)
      const documentCount = entries.length
      if (queryTerms.size === 0 || documentCount === 0) return []

      // The budget is what a complete answer would score. Dividing by it is what
      // keeps the floor comparable across query lengths — and what makes an
      // unmatched rare term pull every score down, which is correct: the record
      // did not answer that part of the question.
      const weights = new Map<string, number>()
      let budget = 0
      for (const term of queryTerms.keys()) {
        // Every term scales with rarity, grammar included: a particle's weight
        // has to stay the same *fraction* of a content term at every corpus
        // size, and only a shared factor gives that.
        const weight =
          termWeight(term, options.identifierWeight) *
          inverseDocumentFrequency(documentCount, documentFrequency.get(term) ?? 0)
        weights.set(term, weight)
        budget += weight
      }
      if (!(budget > 0)) return []

      const hits: MemoryHit[] = []
      for (const entry of entries) {
        let matched = 0
        let matchedTerms: string[] | undefined
        for (const [term, weight] of weights) {
          if (!entry.terms.has(term)) continue
          matched += weight
          // Recorded so the renderer can window the excerpt around the evidence
          // instead of always quoting the head of the record.
          if (matchedTerms === undefined) matchedTerms = []
          matchedTerms.push(term)
        }
        // Presence, not frequency: a record repeats a term because the person
        // restated the fact, which says nothing about whether this query is the
        // one it answers.
        if (matched === 0) continue
        const score = Math.min(1, matched / budget)
        if (score >= query.minScore) {
          hits.push(matchedTerms === undefined
            ? { record: entry.record, score }
            : { record: entry.record, score, matchedTerms })
        }
      }
      // Newer records win ties: a later turn restates the same fact more
      // authoritatively than the turn that first mentioned it.
      hits.sort((left, right) => right.score - left.score || right.record.createdAt - left.record.createdAt)
      return hits.slice(0, query.limit)
    },

    count(): number {
      return entries.length
    },
  }
}
