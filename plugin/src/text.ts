/**
 * Lexical tokenization and weighted overlap scoring for the M0 in-memory store.
 *
 * This stands in for the M1 hybrid index (BM25 + vector), but it is shaped like
 * the thing it replaces rather than like a toy: terms are weighted by rarity
 * (BM25's idf) and by kind, because an identifier and a particle are not equally
 * good evidence. Keeping that shape means tuning here is not throwaway work:
 * the Postgres adapter applies the same weights, with `pg_textsearch` supplying
 * rarity and saturation instead of this file's arithmetic.
 *
 * What no weighting can fix: a paraphrase shares no term. "数据库选型" and
 * "PostgreSQL" are disjoint term sets, and only the embedding half of the M1
 * index closes that gap.
 * @module @deepseek-ai/dsh-memory-core/text
 */

/** Ideographic ranges indexed as bigrams: CJK Unified Ideographs (+Ext A) and compatibility forms. */
const IDEOGRAPH = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/
/** Latin/digit word runs, additionally split on every non-alphanumeric ASCII byte. */
const WORD = /[a-z0-9]+/g

/**
 * Characters that carry grammar rather than content in Chinese.
 *
 * This is a closed class, so the list stays finite — unlike a synonym table,
 * which would have to grow forever and would only duplicate what the embedding
 * half of M1 does properly. It is kept tight on purpose: a character that also
 * sits inside common content words (`下` in 下载, `问` in 问题, `有` in 有效) is
 * left out. Down-weighting a particle sharpens the score; down-weighting a real
 * term loses recall, and those are not symmetric mistakes.
 */
const FUNCTION_CHARS = new Set(
  '的了着过吗呢吧啊呀嘛哦嗯是什怎么样多少哪些个我你他她它们这那哪和与或及也就都而把被',
)

/** Weight of a term whose characters are all function characters ("什么", "多少"). */
const WEIGHT_PURE_FUNCTION = 0.05
/** Weight of a term mixing one function character with content ("型是", "口是"). */
const WEIGHT_MIXED = 0.4
/** Weight of a content bigram or a lone ideograph. */
const WEIGHT_CONTENT = 1

/**
 * Split one text into comparable terms and their counts.
 *
 * A bigram is only produced for a run of two or more adjacent ideographs; a lone
 * ideograph indexes as itself so single-character queries still match. Text is
 * NFKC-normalised first: a full-width "ＰｏｓｔｇｒｅＳＱＬ" from an IME is the
 * same token as the ASCII spelling, and without this it is not.
 * @param text - raw text in any language.
 * @returns term counts; counts exist for the M1 BM25 handoff, not for scoring.
 */
export function tokenize(text: string): Map<string, number> {
  const terms = new Map<string, number>()
  const add = (term: string): void => {
    terms.set(term, (terms.get(term) ?? 0) + 1)
  }
  const lowered = text.normalize('NFKC').toLowerCase()
  for (const word of lowered.match(WORD) ?? []) add(word)
  const run: string[] = []
  const flush = (): void => {
    if (run.length === 1) add(run[0] as string)
    for (let index = 0; index + 1 < run.length; index += 1) {
      add(`${run[index] as string}${run[index + 1] as string}`)
    }
    run.length = 0
  }
  for (const character of lowered) {
    if (IDEOGRAPH.test(character)) run.push(character)
    else flush()
  }
  flush()
  return terms
}

/**
 * Weight one term by what kind of evidence it is.
 *
 * A Latin word or digit run is an identifier (`postgresql`, `8080`, `AC-2048`):
 * it is chosen deliberately, it is rare, and a hit on it is the strongest signal
 * a short query carries, so it outweighs a Chinese bigram that may have been
 * produced by accident of adjacency.
 * @param term - one token from {@link tokenize}.
 * @param identifierWeight - multiplier for Latin and digit terms.
 * @returns a positive weight; function-only terms get a token weight so a query
 * made only of them does not silently rank on grammar.
 */
export function termWeight(term: string, identifierWeight: number): number {
  if (/^[a-z0-9]+$/.test(term)) return identifierWeight
  let total = 0
  let functional = 0
  for (const character of term) {
    total += 1
    if (FUNCTION_CHARS.has(character)) functional += 1
  }
  if (functional === 0) return WEIGHT_CONTENT
  return functional === total ? WEIGHT_PURE_FUNCTION : WEIGHT_MIXED
}

/**
 * BM25's inverse document frequency, in its always-positive form.
 *
 * A term present in every record says nothing about which record to pick, so it
 * lands near zero instead of being treated as evidence.
 *
 * The frequency is floored at one. A term no record contains is usually a
 * boundary artefact of bigram tokenisation ("口是" from 端口|是), not a rare
 * concept, and the unfloored formula makes it the *most* expensive term in the
 * query — at a single-record corpus a perfect match then scores 0.47 instead of
 * 0.81. Flooring says "unseen is at most as surprising as seen once", which
 * keeps the floor's meaning stable while a corpus fills up.
 * @param documentCount - records currently retained.
 * @param documentFrequency - records containing this term.
 * @returns idf in `(0, ∞)`.
 */
export function inverseDocumentFrequency(documentCount: number, documentFrequency: number): number {
  const frequency = Math.max(1, documentFrequency)
  return Math.log(1 + (documentCount - frequency + 0.5) / (frequency + 0.5))
}
