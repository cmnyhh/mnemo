/**
 * The fact layer: a closed set of single-valued properties, each with a
 * validity interval.
 *
 * The lexical store cannot express "out of date". Measured on three real turns
 * (`端口 8080` → `改成 9090` → `再确认 9090`), the query "生产环境端口是多少"
 * scored the current turn at 0.97 and the obsolete one at 0.51, and injected
 * both: overlap is a similarity, and two statements about the same property are
 * maximally similar no matter which one is true. A correction said in different
 * words scored 0.04, so no threshold, weight, or embedding can separate them —
 * what is missing is not similarity but identity (same property) and time
 * (which statement is later).
 *
 * So this layer is deliberately small: six predicates, rule extraction, one
 * active value per (session, predicate, subject). It is not an extractor that
 * reads everything; it is the narrow part where "later wins" must be exact.
 *
 * Extraction is rule-based and conservative. A rule that fires on a sentence
 * with no value produces nothing, which is the desired failure: omission costs
 * a recall, invention costs a wrong instruction that the model will trust.
 * @module @deepseek-ai/dsh-memory-core/facts
 */

import type { FactPredicate, MemoryHit, RecordedFact } from './types.ts'

/** How far after a predicate word its value may sit ("端口固定为 8080"). */
const VALUE_WINDOW = 18

/** How far before the word a value may sit ("8080 端口"). */
const BACK_WINDOW = 12

/**
 * Words that mark "the value after me replaces the previous one".
 *
 * When one is present the *last* value is the current one: `版本从 v1.0 升到
 * v1.2` must yield 1.2, and taking the first match yields 1.0.
 */
const CHANGE = /(?:改成|改为|换成|换为|升到|升为|升级到|更新到|调到|调整到|调整为|设置为|设为|固定为|固定成|现在是|现在用|统一用|统一为)/u

/** Words that make a query ask about the past rather than the present. */
const HISTORICAL = /(?:之前|原来|以前|历史上|上次|旧值|旧的|最初)/u

/**
 * Nouns that turn a number into a count rather than a property value.
 *
 * The gap between a predicate word and its value is usually a verb phrase
 * (`固定为`, `最后定`, `号是`), and no verb list is complete. The nouns that
 * make a following number a count are few, so the guard is a blocklist: it
 * rejects `端口记录 12` without rejecting `端口最后定 7070`.
 */
const COUNT_NOUN = /(?:记录|数量|个数|编号|序号|条数|次数|页数|项数|总数)/u

/** Measure words that turn the number before them into a count (`9090 次`). */
const MEASURE_WORD = /^\s*(?:次|条|个|项|页|行|人)/u

/** Connectors stripped before reading a value. */
const CONNECTOR = /^[\s:：是为=]+/u

/** Negation before a value: `端口不要用 8080`. */
const NEGATION_BEFORE = /(?:不要|不用|别|不能|禁止|无需|不再|弃用|废弃)/u

/** Negation after a value: `8080 不用了`, `8080 是旧值`. */
const NEGATION_AFTER = /^\s*(?:不用了|不再使用|不再用|废弃|弃用|停用|别用|已废弃|已停用|是旧值|已经不用)/u

/** Words dropped from the front of a candidate subject. */
const SUBJECT_PREFIX = /^(?:把|将|这个|那个|该|本|其|对|给|在|用|和|与|的|是|为|之前|原来|以前|现在|目前|当前|刚才)+/u

/** Words that can never be a subject on their own. */
const SUBJECT_REJECT = /^(?:这个|那个|它|其|该|本|之前|原来|以前|现在|目前|当前|刚才)$/u

/** Characters that end a subject run. */
const SUBJECT_STOP = new Set('的了着过吗呢吧啊呀嘛哦嗯是什怎么样多少哪些个我你他她它们这那哪和与或及也就都而把被，。！？；：、\n\t ()（）[]【】')

/** Sentence terminators, used by the question guard. */
const SENTENCE_BREAKS = ['。', '！', '？', '!', '?', '\n'] as const

/** One entry of the closed predicate table. */
interface PredicateSpec {
  readonly id: FactPredicate
  readonly label: string
  readonly keywords: readonly string[]
  readonly value: RegExp
  readonly normalize: (raw: string) => string | undefined
  /** Owner names are ambiguous without a copula, so require `是/为/：`. */
  readonly requireConnector: boolean
}

/** Canonical spellings for the licences this layer recognises. */
const LICENSES = new Map<string, string>([
  ['mit', 'MIT'],
  ['apache-2.0', 'Apache-2.0'],
  ['apache2.0', 'Apache-2.0'],
  ['apache-2', 'Apache-2.0'],
  ['gpl-2.0', 'GPL-2.0'],
  ['gpl-3.0', 'GPL-3.0'],
  ['agpl-3.0', 'AGPL-3.0'],
  ['lgpl-2.1', 'LGPL-2.1'],
  ['lgpl-3.0', 'LGPL-3.0'],
  ['mpl-2.0', 'MPL-2.0'],
  ['bsd-3-clause', 'BSD-3-Clause'],
  ['bsd-2-clause', 'BSD-2-Clause'],
  ['unlicense', 'Unlicense'],
])

/**
 * The closed predicate table.
 *
 * Values are written without a `g` flag; call sites add one when iterating, so
 * no regex carries `lastIndex` state between calls.
 */
const SPECS: readonly PredicateSpec[] = [
  {
    id: 'port',
    label: '端口',
    keywords: ['端口', 'port'],
    // Four-to-five digits are ports; 1-3 are allowed only for the well-known
    // ones listed in `normalize`, because "端口记录 3" must not become port 3.
    value: /(?<![\d.])\d{1,5}(?![\d.])/u,
    normalize: raw => {
      const port = Number(raw)
      if (!Number.isInteger(port) || port < 1 || port > 65535) return undefined
      // One to three digit values are counts far more often than ports, so only
      // the well-known ones are accepted: `端口记录 3` must not become port 3.
      if (port < 100 && !WELL_KNOWN_PORTS.has(port)) return undefined
      return String(port)
    },
    requireConnector: false,
  },
  {
    id: 'version',
    label: '版本',
    keywords: ['版本', 'version'],
    value: /v?\d+\.\d+(?:\.\d+)?(?:-[0-9a-z.]+)?/u,
    normalize: raw => raw.replace(/^v/u, ''),
    requireConnector: false,
  },
  {
    id: 'host',
    label: '地址',
    keywords: ['地址', '主机', '域名', 'ip', 'host'],
    value: /(?:\d{1,3}\.){3}\d{1,3}|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+|localhost/u,
    normalize: raw => raw.toLowerCase(),
    requireConnector: false,
  },
  {
    id: 'path',
    label: '路径',
    keywords: ['路径', '目录', 'path'],
    value: /(?:~|\/)[\w.@/-]+/u,
    normalize: raw => raw.replace(/[.。，,;；:：]+$/u, ''),
    requireConnector: false,
  },
  {
    id: 'owner',
    label: '负责人',
    keywords: ['负责人', '维护人', 'owner'],
    value: /[\u4e00-\u9fff]{2,4}|[A-Za-z][A-Za-z0-9._-]{1,29}/u,
    normalize: raw => raw.trim(),
    requireConnector: true,
  },
  {
    id: 'license',
    label: '许可证',
    keywords: ['许可证', 'license'],
    value: /(?:mit|apache-?2\.0|agpl-?3\.0|lgpl-?[23]\.1|gpl-?[23]\.0|mpl-?2\.0|bsd-?[23]-clause|unlicense)/iu,
    normalize: raw => LICENSES.get(raw.toLowerCase().replace(/\s+/gu, '')),
    requireConnector: false,
  },
]

/** Ports below 100 that are real even without four digits. */
const WELL_KNOWN_PORTS = new Set([21, 22, 25, 53, 80, 88, 110, 143, 389, 443, 465, 587, 636, 993, 995])

/** One value located in the text. */
interface ValueMatch {
  readonly object: string
  readonly start: number
  readonly end: number
}

/** What one segment asserts and what it says is no longer true. */
export interface FactExtraction {
  /** Statements to apply, in reading order. */
  readonly asserted: readonly RecordedFact[]
  /** Values the text says are no longer in use. */
  readonly retracted: readonly Pick<RecordedFact, 'predicate' | 'object'>[]
}

/** A stored fact and its validity interval. */
export interface Fact extends RecordedFact {
  /** Stable identity; the store owns generation. */
  readonly id: string
  /** Session that produced the statement. */
  readonly sessionId: string
  /** Turn the value became current. */
  readonly validFrom: number
  /** Turn it stopped being current; absent while it still is. */
  readonly validTo?: number
  /** Capture time in epoch milliseconds. */
  readonly recordedAt: number
}

/** What a query asks for, resolved against the store. */
export interface FactView {
  /** Predicate the query named, or `undefined` when it named none. */
  readonly predicate: FactPredicate | undefined
  /** Display label of that predicate. */
  readonly label: string | undefined
  /** Subject the query named, `''` when it named none. */
  readonly subject: string
  /** Values that are current and answer this query. */
  readonly active: readonly Fact[]
  /** Values that used to be current; only populated for a historical query. */
  readonly superseded: readonly Fact[]
  /** Whether the query asked about the past (`之前`, `原来`). */
  readonly historical: boolean
}

/** Storage port for facts; the Postgres adapter implements the same three calls. */
export interface FactStore {
  /** Apply one segment's extraction. */
  apply(sessionId: string, turn: number, at: number, extraction: FactExtraction): void
  /**
   * Resolve what a query asks about, in one session.
   *
   * Scoped by session because a fact block is labeled authoritative: reading
   * another session's value does not add background, it states the wrong value
   * as current and then filters the correct record out as superseded.
   * @param sessionId - session the query belongs to.
   * @param text - the turn's query text.
   */
  query(sessionId: string, text: string): FactView
  /** Facts retained, for tests and diagnostics. */
  count(): number
}

/**
 * The display label of a predicate.
 * @param predicate - one id from the closed set.
 * @returns the Chinese label used in the injected block.
 */
export function factLabel(predicate: FactPredicate): string {
  return SPECS.find(spec => spec.id === predicate)?.label ?? predicate
}

/**
 * Drop records whose stated value has been superseded.
 *
 * A record that says 8080 remains the best lexical match for "端口是多少" after
 * the port moved to 9090 — similarity cannot represent "out of date". Only a
 * record whose extracted value contradicts a *current* fact is dropped; a
 * record that merely mentions the subject is not evidence of anything and stays.
 * @param hits - scored hits.
 * @param view - resolved fact view for the same query.
 * @returns hits that do not contradict a current value.
 */
export function dropSupersededHits(hits: readonly MemoryHit[], view: FactView): readonly MemoryHit[] {
  if (view.active.length === 0) return hits
  const current = new Set(view.active.map(fact => `${fact.predicate}|${fact.object}`))
  const predicates = new Set(view.active.map(fact => fact.predicate))
  return hits.filter(hit => !(hit.record.facts ?? []).some(fact => {
    if (!predicates.has(fact.predicate)) return false
    if (current.has(`${fact.predicate}|${fact.object}`)) return false
    // The record states something this query does not currently hold. When the
    // query named a subject, a statement about a *different* subject is not
    // evidence against it: `前端端口 5173，后端端口 9090` answers 前端端口, and
    // dropping it because 9090 is not the front-end port would remove the only
    // record that had the answer.
    if (view.subject !== '' && fact.subject !== '' && fact.subject !== view.subject) return false
    return true
  }))
}

/**
 * Find every occurrence of a predicate's keywords.
 * @param text - source text.
 * @param spec - the predicate to look for.
 * @returns occurrences ordered by position.
 */
function keywordPositions(text: string, spec: PredicateSpec): Array<{ at: number; end: number }> {
  const positions: Array<{ at: number; end: number }> = []
  const lowered = text.toLowerCase()
  for (const keyword of spec.keywords) {
    // A Latin keyword needs boundaries: `port` inside `support` is not a port.
    const pattern = /^[a-z0-9]+$/u.test(keyword)
      ? new RegExp(`(?<![a-z0-9])${keyword}(?![a-z0-9])`, 'gu')
      : new RegExp(keyword, 'gu')
    for (const match of lowered.matchAll(pattern)) {
      if (match.index === undefined) continue
      positions.push({ at: match.index, end: match.index + match[0].length })
    }
  }
  positions.sort((left, right) => left.at - right.at)
  return positions
}

/**
 * The sentence containing an offset.
 * @param text - source text.
 * @param index - offset inside the sentence.
 * @returns the sentence, terminators included.
 */
function sentenceAround(text: string, index: number): string {
  let start = 0
  let end = text.length
  for (const mark of SENTENCE_BREAKS) {
    const before = text.lastIndexOf(mark, index)
    if (before + 1 > start) start = before + 1
    const after = text.indexOf(mark, index)
    if (after >= 0 && after + 1 < end) end = after + 1
  }
  return text.slice(start, end).trim()
}

/**
 * Whether an offset sits inside a question.
 *
 * A question states no value: `端口是 8080 还是 9090？` must not create a fact,
 * and `8080 不用了吗？` must not retire one.
 * @param text - source text.
 * @param index - offset to test.
 * @returns true when the surrounding sentence asks.
 */
function inQuestion(text: string, index: number): boolean {
  const sentence = sentenceAround(text, index)
  return /[？?]\s*$/u.test(sentence) || /[吗呢][？?]?\s*$/u.test(sentence)
}

/**
 * Read a value out of a window that starts at the predicate word.
 * @param window - text after the word (or after a change marker).
 * @param spec - the predicate.
 * @returns the normalised value and its span inside `window`.
 */
function matchValue(window: string, spec: PredicateSpec): ValueMatch | undefined {
  const trimmed = window.replace(CONNECTOR, '')
  const consumed = window.length - trimmed.length
  if (spec.requireConnector && !/[是为：:]/u.test(window.slice(0, consumed))) return undefined
  const pattern = new RegExp(spec.value.source, `${spec.value.flags}g`)
  const match = pattern.exec(trimmed)
  if (match === null) return undefined
  const object = spec.normalize(match[0])
  if (object === undefined) return undefined
  return { object, start: consumed + match.index, end: consumed + match.index + match[0].length }
}

/**
 * Locate the value a predicate word refers to.
 *
 * Forward first (the usual order), then backward for `8080 端口`. Between the
 * word and the value only count nouns are rejected: `端口记录 12` is a count,
 * while the verb phrases that really do sit there (`固定为`, `最后定`, `号是`)
 * cannot be enumerated, so a whitelist would reject the common case.
 * @param text - source text.
 * @param occurrence - keyword span.
 * @param spec - the predicate.
 * @returns the value and its span in `text`.
 */
function valueAt(
  text: string,
  occurrence: { at: number; end: number },
  spec: PredicateSpec,
): ValueMatch | undefined {
  const after = text.slice(occurrence.end, occurrence.end + VALUE_WINDOW)
  const change = CHANGE.exec(after)
  const from = change === null ? occurrence.end : occurrence.end + change.index + change[0].length
  const windowText = text.slice(from, from + VALUE_WINDOW)
  const forward = matchValue(windowText, spec)
  if (forward !== undefined) {
    const gap = text.slice(from, from + forward.start)
    // A negated value still needs to be located: `端口不要用 8080` retires a
    // value instead of asserting one, and the caller decides which it is.
    if (NEGATION_BEFORE.test(gap) || !COUNT_NOUN.test(gap)) {
      // A value followed by a measure word is counting something (`9090 次`).
      if (!MEASURE_WORD.test(text.slice(from + forward.end, from + forward.end + 4))) {
        return { object: forward.object, start: from + forward.start, end: from + forward.end }
      }
    }
  }

  // Backward only for `8080 端口`, where the two are separated by whitespace.
  // A wider gap would read the role prefix in `用户: 负责人` as a value.
  if (spec.requireConnector) return undefined
  const backStart = Math.max(0, occurrence.at - BACK_WINDOW)
  const backward = text.slice(backStart, occurrence.at)
  const pattern = new RegExp(spec.value.source, `${spec.value.flags}g`)
  const matches = [...backward.matchAll(pattern)]
  const last = matches[matches.length - 1]
  if (last === undefined || last.index === undefined) return undefined
  if (!/^\s*$/u.test(backward.slice(last.index + last[0].length))) return undefined
  const object = spec.normalize(last[0])
  if (object === undefined) return undefined
  return { object, start: backStart + last.index, end: backStart + last.index + last[0].length }
}

/**
 * Whether a located value is being retired rather than asserted.
 * @param text - source text.
 * @param value - the located value.
 * @returns true for `不要用 8080` and `8080 不用了`.
 */
function isRetraction(text: string, value: ValueMatch): boolean {
  const before = text.slice(Math.max(0, value.start - 8), value.start)
  const after = text.slice(value.end, value.end + 10)
  return NEGATION_BEFORE.test(before) || NEGATION_AFTER.test(after)
}

/**
 * The qualifier in front of a predicate word, if it reads like one.
 * @param text - source text.
 * @param at - offset of the predicate word.
 * @returns up to six characters before the word, cleaned; `''` when none.
 */
function subjectBefore(text: string, at: number): string {
  let start = at
  let length = 0
  while (start > 0 && length < 6) {
    const character = text[start - 1] as string
    if (SUBJECT_STOP.has(character)) break
    if (!/[\u4e00-\u9fffA-Za-z0-9_-]/u.test(character)) break
    start -= 1
    length += 1
  }
  const cleaned = text.slice(start, at).trim().replace(SUBJECT_PREFIX, '')
  if (cleaned.length < 2 || SUBJECT_REJECT.test(cleaned)) return ''
  return cleaned
}

/**
 * Extract facts from one captured segment.
 *
 * Both the prompt and the reply are read: a person states constraints
 * (`端口改成 9090`), and the model restates them (`端口固定为 9090`). Reading
 * only one side loses half the statements.
 * @param text - one segment's model-visible text.
 * @returns asserted statements and retired values, deduplicated.
 */
export function extractFacts(text: string): FactExtraction {
  if (text.length === 0) return { asserted: [], retracted: [] }
  const asserted = new Map<string, RecordedFact>()
  const retracted = new Map<string, Pick<RecordedFact, 'predicate' | 'object'>>()

  for (const spec of SPECS) {
    for (const occurrence of keywordPositions(text, spec)) {
      if (inQuestion(text, occurrence.at)) continue
      const value = valueAt(text, occurrence, spec)
      if (value === undefined) continue
      if (isRetraction(text, value)) {
        retracted.set(`${spec.id}|${value.object}`, { predicate: spec.id, object: value.object })
        continue
      }
      const subject = subjectBefore(text, occurrence.at)
      asserted.set(`${spec.id}|${subject}|${value.object}`, { predicate: spec.id, subject, object: value.object })
    }
  }

  // A retired value does not need the predicate word beside it: `8080 不用了`
  // is a complete statement, and requiring `端口` would miss the most common
  // way a correction is phrased.
  for (const spec of SPECS) {
    const pattern = new RegExp(`(${spec.value.source})\\s*(?:不用了|不再使用|不再用|废弃|弃用|停用|别用)`, 'gu')
    for (const match of text.matchAll(pattern)) {
      if (match.index === undefined || inQuestion(text, match.index)) continue
      const object = spec.normalize(match[1] ?? '')
      if (object !== undefined) retracted.set(`${spec.id}|${object}`, { predicate: spec.id, object })
    }
  }

  return { asserted: [...asserted.values()], retracted: [...retracted.values()] }
}

/** The predicate a query names, with the offset of its keyword. */
function firstPredicate(text: string): { id: FactPredicate; label: string; at: number } | undefined {
  let best: { id: FactPredicate; label: string; at: number } | undefined
  for (const spec of SPECS) {
    const occurrence = keywordPositions(text, spec)[0]
    if (occurrence === undefined) continue
    if (best === undefined || occurrence.at < best.at) {
      best = { id: spec.id, label: spec.label, at: occurrence.at }
    }
  }
  return best
}

/** A view with no predicate: the answer to any query the layer does not cover. */
const NO_FACTS: FactView = {
  predicate: undefined,
  label: undefined,
  subject: '',
  active: [],
  superseded: [],
  historical: false,
}

/** A statement that becomes current; the store assigns the id. */
export interface FactStatement {
  /** Session the statement was made in. */
  readonly sessionId: string
  /** One id from the closed set. */
  readonly predicate: FactPredicate
  /** Qualifier in front of the predicate word, `''` when there is none. */
  readonly subject: string
  /** The value. */
  readonly object: string
  /** Turn it became current. */
  readonly validFrom: number
  /** Turn it stopped being current inside the same extraction, if it did. */
  readonly validTo?: number
  /** Capture time in epoch milliseconds. */
  readonly recordedAt: number
}

/** What one extraction changes, decided without touching storage. */
export interface FactPlan {
  /** Ids of facts that stop being current at this turn. */
  readonly close: readonly string[]
  /**
   * Current facts whose qualifier is refined, without a new validity interval.
   *
   * `前端端口 5173，后端端口 9090` restates a value the session already holds.
   * Creating a second row would duplicate it; ignoring the qualifier loses the
   * only thing that distinguishes the front end from the back end. The row
   * keeps its `validFrom` because the value never stopped being current — only
   * its name changed.
   */
  readonly retitle: readonly { readonly id: string; readonly subject: string }[]
  /** Statements that become current at this turn. */
  readonly insert: readonly FactStatement[]
}

/**
 * Decide what one extraction changes.
 *
 * The in-memory store and the SQL adapter both apply this plan, so the override
 * rule is written once. A second implementation would drift: the two stores
 * would disagree about which value is current, and the disagreement would only
 * surface as a wrong answer after a restart.
 * @param facts - every retained fact, current and retired.
 * @param sessionId - session the extraction came from; nothing outside it moves.
 * @param turn - turn that produced the extraction.
 * @param at - capture time in epoch milliseconds.
 * @param extraction - statements and retractions read from one segment.
 * @returns the facts to retire and the statements to insert.
 */
export function planFactApplication(
  facts: readonly Fact[],
  sessionId: string,
  turn: number,
  at: number,
  extraction: FactExtraction,
): FactPlan {
  const closed = new Set<string>()
  const isCurrent = (fact: Fact): boolean =>
    fact.validTo === undefined && !closed.has(fact.id)
  const inserted: FactStatement[] = []
  const retitle: { id: string; subject: string }[] = []

  for (const retraction of extraction.retracted) {
    for (const fact of facts) {
      if (!isCurrent(fact)) continue
      if (fact.sessionId !== sessionId) continue
      if (fact.predicate !== retraction.predicate) continue
      if (fact.object !== retraction.object) continue
      closed.add(fact.id)
    }
  }

  for (const assertion of extraction.asserted) {
    // A value is the identity, not the qualifier: `部署端口 8080` and
    // `已记录端口 8080` are one statement, and the subject is display metadata
    // that a restatement should not duplicate.
    const held = facts.find(fact =>
      isCurrent(fact)
      && fact.sessionId === sessionId
      && fact.predicate === assertion.predicate
      && fact.object === assertion.object)
    if (held !== undefined) {
      // The session already holds this value; a qualifier it lacked until now is
      // a name for the same value, not a second one.
      if (held.subject === '' && assertion.subject !== '') {
        retitle.push({ id: held.id, subject: assertion.subject })
      }
      continue
    }
    if (inserted.some(row =>
      row.validTo === undefined
      && row.sessionId === sessionId
      && row.predicate === assertion.predicate
      && row.object === assertion.object)) continue

    for (const fact of facts) {
      if (!isCurrent(fact)) continue
      if (fact.sessionId !== sessionId || fact.predicate !== assertion.predicate) continue
      // A subjectless statement is the session default, so it retires every
      // subject; a qualified one retires only its own.
      if (assertion.subject !== '' && fact.subject !== assertion.subject) continue
      closed.add(fact.id)
    }

    // A later statement in the same segment retires an earlier one exactly as it
    // would on a later turn. Two rows with one subject must not both stay
    // current: the unique index would reject the second insert, and a reader
    // would see two answers where the session stated one.
    for (let index = 0; index < inserted.length; index += 1) {
      const row = inserted[index]
      if (row === undefined || row.validTo !== undefined) continue
      if (row.sessionId !== sessionId || row.predicate !== assertion.predicate) continue
      if (assertion.subject !== '' && row.subject !== assertion.subject) continue
      inserted[index] = { ...row, validTo: turn }
    }

    inserted.push({
      sessionId,
      predicate: assertion.predicate,
      subject: assertion.subject,
      object: assertion.object,
      validFrom: turn,
      recordedAt: at,
    })
  }

  return { close: [...closed], retitle, insert: inserted }
}

/**
 * Resolve what a query asks about against a set of facts.
 *
 * Pure, for the same reason as {@link planFactApplication}: the SQL adapter
 * loads facts and asks this, rather than expressing the rule in SQL where the
 * subjectless-supersedes-all case reads nothing like the sentence it encodes.
 * @param facts - candidate facts, already scoped to one session.
 * @param sessionId - session the query belongs to.
 * @param text - the turn's query text.
 * @returns the values that answer it, plus retired ones for a historical query.
 */
export function resolveFacts(
  facts: readonly Fact[],
  sessionId: string,
  text: string,
): FactView {
  const found = firstPredicate(text)
  if (found === undefined) return NO_FACTS
  const subject = subjectBefore(text, found.at)
  const historical = HISTORICAL.test(text)
  const all = facts.filter(fact =>
    fact.sessionId === sessionId && fact.predicate === found.id)
  let active = all.filter(fact => fact.validTo === undefined)
  if (subject.length > 0) {
    const exact = active.filter(fact => fact.subject === subject)
    active = exact.length > 0 ? exact : active.filter(fact => fact.subject === '')
  }
  // Two statements about one property are only a useful answer when they
  // describe different things (前端 5173 / 后端 8080), and only a qualified
  // subject can say that. Anything else is a correction whose subject prefix
  // was noise, so the newest value wins and no contradiction is shown.
  const byObject = new Map<string, Fact>()
  for (const fact of active) if (!byObject.has(fact.object)) byObject.set(fact.object, fact)
  let distinct = [...byObject.values()]
  if (distinct.length > 1 && !distinct.every(fact => fact.subject.length > 0)) {
    const newest = Math.max(...distinct.map(fact => fact.validFrom))
    distinct = distinct.filter(fact => fact.validFrom === newest)
  }
  return {
    predicate: found.id,
    label: found.label,
    subject,
    active: distinct,
    superseded: historical
      ? all.filter(fact => fact.validTo !== undefined).slice(-4)
      : [],
    historical,
  }
}

/**
 * Create the process-local fact store.
 *
 * One active value per (session, predicate, subject). A statement without a
 * subject is read as the session default and supersedes every subject of that
 * predicate: in a single-project session `端口改成 9090` is about the port that
 * was already being discussed, and keeping `部署端口 8080` alive beside it
 * reproduces exactly the contradiction this layer exists to remove. The cost is
 * that a later subjectless statement also retires an earlier `前端端口 5173`;
 * that is the error direction the objective function prefers — a missing value
 * is recoverable, a stale one is not.
 * @param options - retention cap.
 * @returns a store that lives as long as its plugin fiber.
 */
export function createFactStore(options: { readonly maxFacts: number }): FactStore {
  const facts: Fact[] = []
  let sequence = 0

  const trim = (): void => {
    while (facts.length > options.maxFacts) {
      const closed = facts.findIndex(fact => fact.validTo !== undefined)
      facts.splice(closed >= 0 ? closed : 0, 1)
    }
  }

  return {
    apply(sessionId, turn, at, extraction) {
      const plan = planFactApplication(facts, sessionId, turn, at, extraction)
      const closing = new Set(plan.close)
      for (let index = 0; index < facts.length; index += 1) {
        const fact = facts[index]
        if (fact === undefined || !closing.has(fact.id)) continue
        facts[index] = { ...fact, validTo: turn }
      }
      const renamed = new Map(plan.retitle.map(entry => [entry.id, entry.subject]))
      if (renamed.size > 0) {
        for (let index = 0; index < facts.length; index += 1) {
          const fact = facts[index]
          if (fact === undefined) continue
          const subject = renamed.get(fact.id)
          if (subject === undefined) continue
          facts[index] = { ...fact, subject }
        }
      }
      for (const row of plan.insert) {
        sequence += 1
        facts.push({ id: `fact-${String(sequence)}`, ...row })
      }
      trim()
    },

    query(sessionId, text) {
      return resolveFacts(facts, sessionId, text)
    },

    count() {
      return facts.length
    },
  }
}
