/**
 * Value types shared by the memory store port, the storage adapters, and the
 * plugin entry. Type-only: no runtime code belongs here.
 * @module @deepseek-ai/dsh-memory-core/types
 */

/** What produced a record; selects how the record is rendered back to the model. */
export type MemoryKind = 'turn'

/**
 * The closed predicate set of the fact layer.
 *
 * Closed on purpose: an open set lets the extractor produce `port`, `端口` and
 * `listen_port` for one property, and then the override rule can no longer see
 * that two statements talk about the same thing. Adding a predicate is a code
 * change, reviewed like one.
 */
export type FactPredicate = 'port' | 'version' | 'host' | 'path' | 'owner' | 'license'

/**
 * One (subject, predicate, object) statement found in a captured segment.
 *
 * `subject` is a short qualifier taken from the characters before the predicate
 * word (`前端端口` → `前端`); it is empty when the statement has none, which
 * means "the default entity of this session". It is a heuristic, not entity
 * resolution — see the package README.
 */
export interface RecordedFact {
  /** Which closed property this statement is about. */
  readonly predicate: FactPredicate
  /** Qualifier before the predicate word, or `''`. */
  readonly subject: string
  /** The value, normalised (port range checked, `v` stripped from versions). */
  readonly object: string
}

/**
 * One durable memory record, independent of the storage backend that holds it.
 *
 * `text` is the only model-visible field: the record is what a future turn may
 * read back, so it is written once at capture time and never derived at read
 * time from session history.
 */
export interface MemoryRecord {
  /** Stable identity; the storage backend owns generation. */
  readonly id: string
  /** Session that produced the record. */
  readonly sessionId: string
  /** Producer of the record. */
  readonly kind: MemoryKind
  /** Model-visible text. */
  readonly text: string
  /**
   * What this record answers, when a long turn was split into segments.
   *
   * Rendered as a short prefix (`[问: …]`) so an answer paragraph still says what
   * it is about. Deliberately **not** part of the indexed text: every segment of
   * one turn would otherwise match that turn's question and tie with its
   * siblings.
   */
  readonly context?: string
  /** Capture time in epoch milliseconds. */
  readonly createdAt: number
  /** Turn that produced the record. */
  readonly turn: number
  /**
   * Facts this record asserts, extracted at capture time.
   *
   * Read by recall to drop a record whose value has since been superseded: the
   * record that says 8080 is still lexically the best match for "端口是多少"
   * after the port moved to 9090, and score cannot express "out of date".
   */
  readonly facts?: readonly RecordedFact[]
}

/** One scored recall hit. `score` is comparable only within one recall call. */
export interface MemoryHit {
  /** The matched record. */
  readonly record: MemoryRecord
  /** Relevance in `[0, 1]`; the scale is backend-defined but monotone. */
  readonly score: number
  /**
   * Query terms this record contained, in the tokeniser's normalised form.
   *
   * The store scores against the whole record but the plugin renders at most
   * `maxLineChars` of it, so the renderer needs to know where the evidence is:
   * without this, a record that matches on its last sentence is injected as its
   * first 240 characters, which may answer nothing. Optional because a backend
   * may not surface postings; the renderer then keeps both ends instead.
   */
  readonly matchedTerms?: readonly string[]
}

/** A resolved recall request: the caller has already applied every default. */
export interface RecallQuery {
  /** Query text, already trimmed and non-empty. */
  readonly text: string
  /** Maximum hits to return. */
  readonly limit: number
  /** Hits scoring below this value are dropped, so an irrelevant query abstains. */
  readonly minScore: number
}

/**
 * Storage port the plugin talks to.
 *
 * M0 ships exactly one implementation ({@link createInMemoryStore}); the
 * Postgres/Qdrant adapters replace it without changing the plugin, which is why
 * the port stays this narrow. `record` is synchronous in M0 only because the
 * in-memory adapter cannot block; the Postgres adapter enqueues a job instead
 * of widening this signature.
 */
export interface MemoryStore {
  /**
   * Persist one record.
   * @param record - the record to store; the caller owns deduplication by turn.
   */
  record(record: MemoryRecord): void
  /**
   * Return the most relevant records for one query.
   * @param query - resolved query, limit, and floor.
   * @returns hits ordered by descending score; empty when nothing clears the floor.
   */
  recall(query: RecallQuery): readonly MemoryHit[]
}
