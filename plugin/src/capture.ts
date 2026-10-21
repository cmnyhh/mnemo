/**
 * Capture-time segmentation: turn one finished exchange into memory records.
 *
 * One record per turn makes the turn the retrieval unit, which fails twice: a
 * long turn is truncated when it is injected, and a short query that matches one
 * sentence is diluted by every unmatched sentence in the same blob. MemOS's L1
 * trace avoids this by storing a small grounded unit with its context; this file
 * does the same thing without an extractor — each record is one paragraph of
 * what somebody actually said, and the prompt it answered travels beside it.
 *
 * Segmentation is mechanical (blank lines, then sentence ends, then a hard cut),
 * so nothing is summarised and every record is still traceable to its turn.
 * @module @deepseek-ai/dsh-memory-core/capture
 */

/** Characters a role prefix (`用户: `) spends before the text starts. */
const ROLE_PREFIX_CHARS = 4

/** How much of the prompt travels with a detached answer segment. */
const CONTEXT_CHARS = 60

/** One record produced from a finished exchange. */
export interface CapturedSegment {
  /** Model-visible text, already carrying its role prefix. */
  readonly text: string
  /**
   * The prompt this segment answers, when the parse detached it from the prompt.
   *
   * Rendered as a short prefix so a bare `助手: 8080` still says what it answers.
   * Not indexed: putting the question in the text would make every segment of a
   * turn match the same query terms.
   */
  readonly context?: string
}

/**
 * Split one exchange (a human message and the replies to it) into records.
 *
 * A short exchange stays one record, which keeps the common case a single hit.
 * Only when the whole exchange exceeds `segmentChars` does it become the prompt
 * record plus one record per answer paragraph.
 * @param prompt - the human message, already trimmed and non-empty.
 * @param replies - assistant texts in order; the last substantive one is the answer.
 * @param segmentChars - target ceiling for one record's text.
 * @returns at least one segment; ordered prompt-first.
 */
export function captureSegments(
  prompt: string,
  replies: readonly string[],
  segmentChars: number,
): readonly CapturedSegment[] {
  const answer = lastSubstantive(replies)
  const whole = answer === undefined ? `用户: ${prompt}` : `用户: ${prompt}\n助手: ${answer}`
  if (whole.length <= segmentChars) return [{ text: whole }]

  const context = prompt.length <= CONTEXT_CHARS ? prompt : `${prompt.slice(0, CONTEXT_CHARS)}…`
  const segments: CapturedSegment[] = []
  for (const part of splitText(prompt, segmentChars - ROLE_PREFIX_CHARS)) {
    segments.push({ text: `用户: ${part}` })
  }
  if (answer !== undefined) {
    for (const part of splitText(answer, segmentChars - ROLE_PREFIX_CHARS)) {
      segments.push({ text: `助手: ${part}`, context })
    }
  }
  return segments
}

/**
 * The reply that carries the answer.
 *
 * Mid-turn assistant messages are narration ("先看一下文件") that precedes a tool
 * call; recording them would add noise to every later query that shares a word
 * with them. The last substantive message is the one that concludes the turn.
 * @param replies - assistant texts in order.
 * @returns the last non-empty reply, or `undefined` when there is none.
 */
function lastSubstantive(replies: readonly string[]): string | undefined {
  for (let index = replies.length - 1; index >= 0; index -= 1) {
    const text = replies[index]?.trim()
    if (text !== undefined && text.length > 0) return text
  }
  return undefined
}

/**
 * Break text into chunks of at most `maxChars`, preferring line and sentence
 * boundaries over an arbitrary cut.
 *
 * Paragraphs are **not** packed together to fill a chunk. Adjacent lines in a
 * reply are not necessarily about the same thing, and merging them recreates the
 * problem segmentation exists to remove: a query that matches one line would
 * drag its neighbour into the injected excerpt.
 * @param text - source text.
 * @param maxChars - ceiling for one chunk.
 * @returns at least one chunk; empty input yields `[]`.
 */
function splitText(text: string, maxChars: number): string[] {
  const units = text
    .split(/\n+/u)
    .map(unit => unit.trim())
    .filter(unit => unit.length > 0)

  const chunks: string[] = []
  for (const unit of units) chunks.push(...splitLong(unit, maxChars))
  return chunks.length === 0 ? [text.slice(0, maxChars)] : chunks
}

/**
 * Split a single line that is longer than one chunk, at sentence ends.
 * @param unit - a line with no newlines.
 * @param maxChars - ceiling for one chunk.
 * @returns one or more chunks.
 */
function splitLong(unit: string, maxChars: number): string[] {
  if (unit.length <= maxChars) return [unit]
  const sentences = unit.split(/(?<=[。！？；.!?;])\s*/u).filter(sentence => sentence.length > 0)
  const chunks: string[] = []
  let current = ''
  for (const sentence of sentences) {
    if (sentence.length > maxChars) {
      if (current.length > 0) {
        chunks.push(current)
        current = ''
      }
      for (let at = 0; at < sentence.length; at += maxChars) {
        chunks.push(sentence.slice(at, at + maxChars))
      }
      continue
    }
    if (current.length === 0) current = sentence
    else if (current.length + sentence.length <= maxChars) current += sentence
    else {
      chunks.push(current)
      current = sentence
    }
  }
  if (current.length > 0) chunks.push(current)
  return chunks.length === 0 ? [unit.slice(0, maxChars)] : chunks
}
