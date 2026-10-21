/**
 * Long-term memory for the harness: capture each finished turn, recall relevant
 * records into the first step of a later turn, and expose the same recall as the
 * `memory_search` tool.
 *
 * M0 scope: the three extension points (`session/event`, `agent/pre-step`,
 * `ctx.tools.register`) are wired end to end over an in-memory store, so the
 * plugin is runnable and testable before any database exists. Capture is
 * extractive — it records what the user and the model actually said — and recall
 * is lexical. On top of the records, a rule-based fact layer keeps the six
 * single-valued properties where "later wins" must be exact; a score cannot say
 * that a record is out of date. Both are replaced in M1 by the extraction
 * pipeline and the hybrid BM25+vector index without changing this file's
 * structure.
 * @module @deepseek-ai/dsh-memory-core
 */

import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import type { PreStepDecision } from '@deepseek-ai/dsh-agent'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import type { ContentBlock, ContextFormed, UserMessage } from '@deepseek-ai/dsh-llm'
import type { Session, SessionEvent } from '@deepseek-ai/dsh-session'
import { defineTool } from '@deepseek-ai/dsh-tools'
import type { MemoryHit, MemoryStore } from './types.ts'
import { captureSegments } from './capture.ts'
import { createFactStore, dropSupersededHits, extractFacts, factLabel } from './facts.ts'
import type { FactStore } from './facts.ts'

/** A store that may also be able to commit its queued writes. */
type Flushable<T> = T & { readonly flush?: () => Promise<void> }
import {
  FACT_HEADER,
  TOOL_LINE_CHARS,
  TOOL_LINE_OVERHEAD,
  renderFactList,
  renderHitList,
  renderInjection,
} from './render.ts'
import { createInMemoryStore } from './store.ts'
import { Outbox, remotePinned, remoteSearch } from './remote.ts'
import { syncAgentsFile } from './agents.ts'
import { decideCapture } from './gate.ts'
import { resolveToken } from './token.ts'
import { liveString } from './live.ts'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { RemoteOptions } from './remote.ts'

declare module '@deepseek-ai/dsh-llm' {
  interface MessageSourceMap {
    'memory-core': { kind: 'memory-core' } & ContextFormed
  }
}

/** The source kind stamped on every context this plugin injects. */
export const name = 'memory-core'

/** Host services this plugin consumes: the tool registry, for `memory_search`. */
export const inject = ['tools']

/** Capture, recall, and rendering bounds. Every field is validated at plugin load. */
export interface Config {
  /** Recalled records per injected block (default 6). */
  topK?: number
  /**
   * Minimum weighted coverage in `[0, 1]` for a record to count as relevant
   * (default 0.4). Calibrated so that a query whose missing terms are grammar
   * still clears it while one whose missing term is the concept asked about does
   * not; see the package README for the measured curve.
   */
  minScore?: number
  /**
   * Multiplier on Latin/digit query terms relative to a Chinese bigram
   * (default 1.5). Identifiers are the strongest evidence a short query carries.
   */
  identifierWeight?: number
  /** Character budget for the whole injected block, header included (default 1200). */
  contextBudgetChars?: number
  /** Characters kept from any single recalled record (default 240). */
  maxLineChars?: number
  /**
   * Target size of one captured record in characters (default 240).
   *
   * A turn longer than this is stored as its prompt plus one record per answer
   * paragraph, so a small fact is not buried in a long turn: the retrieval unit
   * is the paragraph, not the turn. Set it above `maxLineChars` and injection
   * starts truncating records it could have shown whole.
   */
  segmentChars?: number
  /**
   * Character budget for one `memory_search` result (default 4000).
   *
   * The injected block has `contextBudgetChars`; the tool had no ceiling at all,
   * so a model-supplied `limit` could pour the whole corpus into one turn. The
   * budget truncates the hit list; `limit` still caps the count.
   */
  toolBudgetChars?: number
  /**
   * Records retained before the oldest turn is evicted (default 5000).
   *
   * The cap counts records, and a long turn is several of them, so the number of
   * turns it holds is smaller: measured at about seven records per 450-character
   * turn. Eviction drops a whole turn, never a fragment of one.
   */
  maxRecords?: number
  /**
   * Track single-valued properties (port, version, host, path, owner, licence)
   * with their validity interval (default true).
   *
   * This is what makes a correction override instead of accumulate: a record
   * that states 8080 stays lexically similar to "端口是多少" forever, and only
   * an explicit current value can say it is out of date.
   */
  facts?: boolean
  /** Facts retained before the oldest is evicted (default 1000). */
  maxFacts?: number
  /**
   * A prepared record store, replacing the process-local one.
   *
   * The plugin does not build a database connection: a host that wants records
   * to survive a restart creates the adapter (see
   * `createPostgresStore`), waits for its `ready()`, and hands it over. `flush()`
   * is called after every captured turn when the store has one.
   */
  store?: MemoryStore & { flush?: () => Promise<void> }
  /** A prepared fact store; ignored when {@link facts} is `false`. */
  factStore?: FactStore & { flush?: () => Promise<void> }
  /**
   * Base URL of a standalone memory service, e.g. `http://127.0.0.1:8090`.
   *
   * Setting this turns the plugin into a thin client: capture still happens
   * here (only the plugin sees the turn), but recall and write are HTTP calls,
   * and none of the in-process store, fact extraction, or rendering runs. That
   * is the deployment the design calls for — one service holding one copy of
   * the memory, several clients reading it — and the reason {@link store} and
   * {@link factStore} are ignored when it is set.
   *
   * Leave it unset for the in-process mode: no service needed, and everything
   * is lost on restart.
   */
  serviceUrl?: string
  /**
   * Bearer token for {@link serviceUrl}. Required, and validated at load, when
   * the URL is set.
   *
   * Secret: the settings form shows only that a value exists, never the value.
   */
  serviceToken?: string
  /** Project scope sent with every call; unset means tenant-wide. */
  projectId?: string
  /**
   * Hard ceiling for one service call in ms (default 1200).
   *
   * Recall sits in front of the model's first step, so the ceiling is the
   * reason a slow service degrades to "no memory this turn" instead of a slow
   * turn. Writes are queued and retried, so they may exceed it — they are off
   * the critical path.
   */
  serviceTimeoutMs?: number
  /**
   * Hard ceiling for one write (`/ingest`) in ms (default 20000).
   *
   * 写要跑抽取模型（0.4–7 s），和读不是一个量级。曾经共用 `serviceTimeoutMs`，
   * 结果每次写入都在 1.2 s 被客户端掐断（nginx 记 499），而服务端仍照常落库，
   * 队列却以为失败、保留重发。
   */
  writeTimeoutMs?: number
  /** Model-facing name of the recall tool (default `memory_search`). */
  toolName?: string
  /**
   * Model-facing name of the write tool (default `memory_write`).
   *
   * The turn gate is textual: it only writes a turn that says "记住", reports a
   * change, or repairs a failure. A decision reached in ordinary discussion
   * carries none of those words, so the model needs its own channel to keep it.
   */
  writeToolName?: string
  /**
   * Path of the file that receives the pinned records (default `$DSH_HOME/AGENTS.md`,
   * falling back to `~/.dsh/AGENTS.md`).
   *
   * Only used when {@link t0WriteEnabled} is on. Kept configurable because the
   * harness may be started with a different home, and guessing that path wrong
   * means silently writing a file nobody reads.
   */
  agentsMd?: string
  /**
   * Project the pinned (T0) records into the managed block of `agentsMd`
   * (default `false`).
   *
   * Off by default: this writes a file the user owns, and a memory plugin should
   * not start editing it because it happens to be installed. Turning it on gives
   * T0 its durable half — the block survives a service outage and is readable
   * and editable by the person it describes.
   */
  t0WriteEnabled?: boolean
  /** Character ceiling for the managed block (default 2048). */
  agentsMaxChars?: number
  /** Minimum minutes between two write attempts (default 30). */
  agentsSyncMinutes?: number
  /**
   * Only write a turn that looks worth remembering (default `true`).
   *
   * Extraction on every turn costs more model calls than the conversation, and
   * most turns produce records that only add noise to recall. The gate keeps
   * explicit "记住…", changes, and repaired failures. Turn it off to capture
   * everything, which is mainly useful when tuning extraction.
   */
  writeGate?: boolean
  /** Turns shorter than this (prompt + replies) are never written (default 24). */
  writeGateMinChars?: number
}

/** Schemastery validation for {@link Config}. */
// `volatile` fields are holders at runtime, which the source-shaped
// annotation below cannot express; `liveString` is how they are read.
export const Config = z.object({
  topK: z.number().default(6),
  minScore: z.number().default(0.4),
  identifierWeight: z.number().default(1.5),
  contextBudgetChars: z.number().default(1200),
  maxLineChars: z.number().default(240),
  segmentChars: z.number().default(240),
  toolBudgetChars: z.number().default(4000),
  maxRecords: z.number().default(5000),
  facts: z.boolean().default(true),
  maxFacts: z.number().default(1000),
  store: z.any(),
  factStore: z.any(),
  serviceUrl: z.string().volatile(),
  serviceToken: z.string().default('').role('secret').volatile(),
  projectId: z.string(),
  serviceTimeoutMs: z.number().default(1200),
  writeTimeoutMs: z.number().default(20000),
  toolName: z.string().default('memory_search'),
  writeToolName: z.string().default('memory_write'),
  agentsMd: z.string(),
  t0WriteEnabled: z.boolean().default(false),
  agentsMaxChars: z.number().default(2048),
  agentsSyncMinutes: z.number().default(30),
  writeGate: z.boolean().default(true),
  writeGateMinChars: z.number().default(24),
}) as unknown as z<Config>

/** Argument schema for `memory_search`; the map is an implicit open object root. */
const SEARCH_PARAMETERS = {
  query: {
    type: 'string',
    required: true,
    description: 'What to look up in long-term memory. Natural language; the most specific terms first.',
  },
  limit: {
    type: 'integer',
    description: 'Maximum records to return. Omit to use the plugin default.',
  },
} as const

/** Output schema for `memory_search`. */
const SEARCH_OUTPUT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    facts: {
      type: 'array',
      required: true,
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          label: { type: 'string', required: true },
          value: { type: 'string', required: true },
        },
      },
    },
    hits: {
      type: 'array',
      required: true,
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          id: { type: 'string', required: true },
          text: { type: 'string', required: true },
          score: { type: 'number', required: true },
          turn: { type: 'integer', required: true },
        },
      },
    },
    /**
     * 当 hits 为空时，替代空结果的那段话：要么是服务端给的「最近记忆」兜底块，
     * 要么是请求根本没成功的原因。有它的时候不再输出 "No memory matched"，
     * 因为那句话在两种情况下都不准确。
     */
    note: { type: 'string' },
  },
} as const

/** One human message and the replies to it, accumulated inside a turn. */
interface Exchange {
  readonly prompt: string
  readonly replies: string[]
}

/** Argument schema for `memory_write`. */
const WRITE_PARAMETERS = {
  text: {
    type: 'string',
    required: true,
    description: 'One self-contained statement to remember, in the third person. '
      + 'Not a question, not a transcript, not a description of this tool call.',
  },
} as const

/** Upper bound for one `memory_write` statement. Longer text is a turn, not a fact. */
const WRITE_MAX_CHARS = 2000

/** Output schema for `memory_write`. */
const WRITE_OUTPUT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    queued: { type: 'boolean', required: true },
    note: { type: 'string', required: true },
  },
} as const

/** One turn being accumulated between `turn/start` and `turn/end`. */
interface TurnDraft {
  readonly turn: number
  readonly exchanges: Exchange[]
}

/**
 * Concatenate the text blocks of one message body.
 *
 * Only `text` blocks are captured: tool calls and results are re-derivable from
 * the session log, and a memory record that quotes a raw tool payload is noise.
 * @param content - message content blocks.
 * @returns trimmed text, empty when the message carries none.
 */
function textOf(content: readonly ContentBlock[]): string {
  return content
    .flatMap(block => block.type === 'text' ? [block.text] : [])
    .join('\n')
    .trim()
}

/**
 * Build the recall query from the messages claimed for one step.
 *
 * Only `user`-sourced messages are used: they are what a person asked. Plugin
 * context, tool results, and injected instructions are excluded so recall never
 * feeds on its own output.
 * @param messages - messages entering the step.
 * @returns the query text, or `undefined` when the step carries no human prompt.
 */
function queryText(messages: readonly UserMessage[]): string | undefined {
  const parts: string[] = []
  for (const message of messages) {
    if (message.source.kind !== 'user') continue
    const text = textOf(message.content)
    if (text.length > 0) parts.push(text)
  }
  return parts.length === 0 ? undefined : parts.join('\n')
}

/** Reject a numeric bound that cannot be honored, at load time rather than per turn. */
function requireInteger(label: string, value: number, minimum: number): number {
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`memory-core: ${label} must be an integer >= ${String(minimum)}, got ${String(value)}`)
  }
  return value
}

/** Characters the fact block may take from the injection budget. */
const FACT_BUDGET_CHARS = 400

/**
 * Join the blocks injected into one turn. Pinned comes first: a pinned record is
 * a standing instruction ("always answer in Chinese"), and standing instructions
 * read better before "here is what might be relevant this time".
 */
function joinBlocks(...parts: (string | undefined)[]): string | undefined {
  const kept = parts.filter((part): part is string =>
    part !== undefined && part.trim().length > 0)
  return kept.length === 0 ? undefined : kept.join('\n\n')
}

/**
 * Turns held in the thin client's write-behind queue before the oldest is dropped.
 *
 * Generous enough to ride out a service restart or a dropped network for a
 * working session, small enough that a permanently misconfigured `serviceUrl`
 * cannot grow the plugin into an out-of-memory kill.
 */
const OUTBOX_CAPACITY = 500

/**
 * Install capture, recall, and the recall tool.
 * @param ctx - plugin context; every listener and registration is scoped to it.
 * @param config - validated {@link Config}; bounds are re-checked here fail-loud.
 */
/** `$DSH_HOME/AGENTS.md`, or the packaged default when the variable is unset. */
function resolveAgentsPath(configured: string | undefined): string {
  const explicit = configured?.trim()
  if (explicit !== undefined && explicit.length > 0) return explicit
  const home = process.env.DSH_HOME?.trim()
  const base = home !== undefined && home.length > 0 ? home : join(homedir(), '.dsh')
  return join(base, 'AGENTS.md')
}

export function apply(ctx: Context, config: Config): void {
  // schemastery's .default() guarantees the fields are set after validation.
  const topK = requireInteger('topK', config.topK as number, 1)
  const minScore = config.minScore as number
  if (!(minScore >= 0 && minScore <= 1)) {
    throw new Error(`memory-core: minScore must be within [0, 1], got ${String(minScore)}`)
  }
  const identifierWeight = config.identifierWeight as number
  if (!(identifierWeight > 0)) {
    throw new Error(`memory-core: identifierWeight must be positive, got ${String(identifierWeight)}`)
  }
  const contextBudgetChars = requireInteger('contextBudgetChars', config.contextBudgetChars as number, 1)
  const maxLineChars = requireInteger('maxLineChars', config.maxLineChars as number, 1)
  const segmentChars = requireInteger('segmentChars', config.segmentChars as number, 20)
  // A record larger than the render budget is delivered as an excerpt, and an
  // excerpt of a record that answers nothing is worse than no record: it spends
  // the budget on text the model must then disregard.
  if (segmentChars > maxLineChars) {
    throw new Error(
      `memory-core: segmentChars (${String(segmentChars)}) must not exceed maxLineChars (${String(maxLineChars)})`,
    )
  }
  const toolBudgetChars = requireInteger('toolBudgetChars', config.toolBudgetChars as number, 1)
  const maxRecords = requireInteger('maxRecords', config.maxRecords as number, 1)
  const maxFacts = requireInteger('maxFacts', config.maxFacts as number, 1)
  const toolName = config.toolName as string
  if (toolName.trim().length === 0) throw new Error('memory-core: toolName must not be empty')
  const writeToolName = config.writeToolName as string
  if (writeToolName.trim().length === 0) throw new Error('memory-core: writeToolName must not be empty')
  if (writeToolName === toolName) throw new Error('memory-core: writeToolName must differ from toolName')

  const writeGate = config.writeGate !== false
  const writeGateMinChars = requireInteger('writeGateMinChars', config.writeGateMinChars as number, 1)

  const t0WriteEnabled = config.t0WriteEnabled === true
  const agentsMaxChars = requireInteger('agentsMaxChars', config.agentsMaxChars as number, 256)
  const agentsSyncMs = requireInteger('agentsSyncMinutes', config.agentsSyncMinutes as number, 1) * 60_000
  const agentsFile = t0WriteEnabled ? resolveAgentsPath(config.agentsMd) : undefined
  let lastAgentsSync = 0

  const serviceTimeoutMs = requireInteger('serviceTimeoutMs', config.serviceTimeoutMs as number, 1)
  const writeTimeoutMs = requireInteger('writeTimeoutMs', config.writeTimeoutMs as number, 1000)
  // Volatile: the settings panel may rewrite the URL and the token while this
  // runs, so both are read per use instead of snapshotted here.
  const readUrl = liveString(config.serviceUrl)
  const readToken = liveString(config.serviceToken)
  const serviceUrl = readUrl()
  const remote: RemoteOptions | undefined = serviceUrl === undefined || serviceUrl.length === 0
    ? undefined
    : {
        baseUrl: serviceUrl,
        // Volatile: the settings panel can rewrite the token while this runs,
        // so read it per request instead of snapshotting it here.
        token: () => resolveToken(readToken()),
        projectId: config.projectId,
        timeoutMs: serviceTimeoutMs,
        writeTimeoutMs,
      }
  // No token yet is a normal state, not a load failure.
  //
  // It used to throw here, so a missing token surfaced immediately instead of
  // as 401s on every turn. That was wrong once the plugin grew a settings
  // panel: the panel edits an entry's config, and only *active* entries have
  // one — throwing means the entry never activates, so the only surface that
  // can supply the token never appears. The plugin therefore activates in an
  // unconfigured state, says so once, and skips calls until a token arrives;
  // because the token is read per request, saving it in the panel takes effect
  // on the next turn with no restart.
  if (remote !== undefined && remote.token().length === 0) {
    ctx.logger?.warn?.(
      'memory-core: serviceUrl 已设置但没有可用令牌，先按未配置运行；'
      + '在「插件」页的 memory-core 面板填入令牌即可生效（无需重启）。'
      + '也支持 env:NAME 与 file:/path 两种写法。',
    )
  }

  const warn = (message: string, error?: unknown): void => {
    ctx.logger?.warn?.(message, error)
  }

  /**
   * Write the pinned set to `AGENTS.md`, at most once per `agentsSyncMinutes`.
   *
   * Not awaited: this is a side effect of a turn, and a slow disk must not delay
   * the step. The interval exists because a write can make an editor reload the
   * file — doing that every turn would be user-visible noise, and the content is
   * unchanged between pins anyway.
   */
  const syncAgents = (items: Parameters<typeof syncAgentsFile>[1]): void => {
    if (agentsFile === undefined) return
    const now = Date.now()
    if (now - lastAgentsSync < agentsSyncMs) return
    lastAgentsSync = now
    void syncAgentsFile({ file: agentsFile, maxChars: agentsMaxChars }, items)
      .then((result) => {
        if (result.status === 'written') {
          ctx.logger?.info?.(`memory-core: AGENTS.md ${result.detail}`)
        } else if (result.status === 'skipped') {
          warn(`memory-core: AGENTS.md ${result.detail}`)
        }
      })
      .catch((error: unknown) => { warn('memory-core: AGENTS.md 同步异常', error) })
  }

  // Two modes, one wiring. `remote` decides whether there is a local store at
  // all: in thin-client mode the service owns every record, so keeping a
  // second copy here would give the process an answer the service disagrees
  // with after a restart.
  const store: Flushable<MemoryStore> | undefined = remote === undefined
    ? config.store ?? createInMemoryStore({ maxRecords, identifierWeight })
    : undefined
  const factStore: Flushable<FactStore> | undefined = remote !== undefined || config.facts === false
    ? undefined
    : config.factStore ?? createFactStore({ maxFacts })
  const outbox = remote === undefined ? undefined : new Outbox(remote, OUTBOX_CAPACITY, warn)
  /**
   * Commit queued writes, if the caller supplied a durable store.
   *
   * Not awaited: `session/event` is a synchronous fold of the log, and a durable
   * adapter's own `flush` is the retry unit. A failure is reported rather than
   * swallowed, because a store that has silently stopped writing looks exactly
   * like one that works until the next restart.
   */
  function persist(): void {
    const recordFlush = store?.flush
    const factFlush = factStore?.flush
    const run = (flush: () => Promise<void>): void => {
      void Promise.resolve()
        .then(flush)
        .catch((error: unknown) => {
          ctx.logger?.warn?.('memory-core: flush failed; records are still in memory', error)
        })
    }
    if (recordFlush !== undefined) run(() => recordFlush())
    if (factFlush !== undefined) run(() => factFlush())
  }
  /** In-flight turn per session; dropped at `turn/end` so a draft cannot outlive its turn. */
  const drafts = new WeakMap<Session, TurnDraft>()
  let sequence = 0

  function recall(sessionId: string | undefined, text: string, limit: number): readonly MemoryHit[] {
    // Thin-client mode has no local index; the service is the only source, and
    // it is asked on the paths that can await (pre-step, tool), never here.
    if (store === undefined) return []
    const hits = store.recall({ text, limit, minScore })
    // A tool call can arrive without an agent; without a session there is no
    // scoped fact view, and an unscoped one would assert another session's
    // value as current. Records still come back, facts do not.
    const view = sessionId === undefined ? undefined : factStore?.query(sessionId, text)
    return view === undefined ? hits : dropSupersededHits(hits, view)
  }

  // Capture. `session/event` reports every accepted durable event, so the draft
  // is a fold of the log rather than a read of it: no synchronous log read, and
  // a resumed session simply starts folding at its first new `turn/start`.
  ctx.on('session/event', (session: Session, event: SessionEvent): void => {
    switch (event.type) {
      case 'turn/start':
        drafts.set(session, { turn: event.data.turn, exchanges: [] })
        return
      case 'user/message': {
        const draft = drafts.get(session)
        // Our own injected block and other plugin context are not recallable
        // material; only what a person typed is.
        if (draft === undefined || event.data.source.kind !== 'user') return
        const text = textOf(event.data.content)
        // A steering message mid-turn opens its own exchange: it asked a
        // different question, so it answers it separately.
        if (text.length > 0) draft.exchanges.push({ prompt: text, replies: [] })
        return
      }
      case 'assistant/message': {
        const draft = drafts.get(session)
        if (draft === undefined) return
        const exchange = draft.exchanges[draft.exchanges.length - 1]
        if (exchange === undefined) return
        const text = textOf(event.data.message.content)
        if (text.length > 0) exchange.replies.push(text)
        return
      }
      case 'turn/end': {
        const draft = drafts.get(session)
        drafts.delete(session)
        // A turn with no human prompt (a plugin-triggered continuation) has
        // nothing worth remembering, and storing it would let the system quote
        // itself back as if a person had said it.
        if (draft === undefined) return
        // The gate runs before the mode split, not inside the thin-client branch:
        // small talk dilutes recall in the in-process store exactly as it does in
        // the service, and a client that filters only when a service is reachable
        // would behave differently in its two deployments for no stated reason.
        const gated = draft.exchanges.map((exchange, offset) => ({
          exchange,
          offset,
          gate: decideCapture(
            { prompt: exchange.prompt, replies: exchange.replies },
            { enabled: writeGate, minChars: writeGateMinChars },
          ),
        }))
        const kept = gated.filter((entry) => {
          if (entry.gate.capture) return true
          ctx.logger?.debug?.(`memory-core: 跳过写入（${entry.gate.reason}）`)
          return false
        })
        if (outbox !== undefined) {
          // Thin client: post the exchanges whole and let the service segment
          // them. Segmenting here would put a retrieval decision on the client,
          // which is the one thing this mode exists to avoid — the service
          // cannot re-tune what it never sees.
          for (const { exchange, offset, gate } of kept) {
            outbox.push({
              idempotencyKey: `${session.id}:${String(draft.turn)}:${String(offset)}`,
              projectId: config.projectId,
              sessionId: session.id,
              turnIndex: draft.turn,
              messages: [
                { role: 'user', content: exchange.prompt },
                ...exchange.replies.map(reply => ({ role: 'assistant', content: reply })),
              ],
              ...(gate.trigger === 'none' ? {} : { trigger: gate.trigger }),
            })
          }
          return
        }
        if (store === undefined) return
        for (const { exchange } of kept) {
          for (const segment of captureSegments(exchange.prompt, exchange.replies, segmentChars)) {
            // Extraction runs at capture, not at recall: the value is what the
            // turn said at the time it said it, and re-reading old text later
            // would let a newer record rewrite history.
            const extraction = extractFacts(segment.text)
            factStore?.apply(session.id, draft.turn, event.time, extraction)
            sequence += 1
            store.record({
              id: `mem-${String(sequence)}`,
              sessionId: session.id,
              kind: 'turn',
              text: segment.text,
              ...(segment.context === undefined ? {} : { context: segment.context }),
              createdAt: event.time,
              turn: draft.turn,
              ...(extraction.asserted.length === 0 ? {} : { facts: extraction.asserted }),
            })
          }
        }
        persist()
        return
      }
      default:
        return
    }
  })

  // Recall. Preparing a step is the only moment the plugin can change what the
  // model reads, and step 1 is the only step whose request opens a turn.
  ctx.on('agent/pre-step', async (
    { agent, messages, step, signal },
    next,
  ): Promise<PreStepDecision> => {
    const decision = await next()
    if (decision.kind === 'reject' || signal.aborted) return decision
    // Step 1 opens a turn's request, and a turn opens exactly one; every later
    // step is the same prompt already carrying the block.
    if (step !== 1) return decision
    const query = queryText(messages)
    if (query === undefined) return decision
    const sessionId = agent.session.id
    let block: string | undefined
    if (remote === undefined) {
      block = renderInjection(
        factStore?.query(sessionId, query),
        recall(sessionId, query, topK),
        {
          budgetChars: contextBudgetChars,
          factBudgetChars: FACT_BUDGET_CHARS,
          maxLineChars,
        },
      )
    } else {
      // The service returns both blocks already rendered against its own budget;
      // re-rendering here would spend `contextBudgetChars` twice and let a
      // client-side truncation cut a line the service prioritised.
      //
      // Pinned and recall are fetched together: neither depends on the other, and
      // serialising them would add a round trip to every turn's first step.
      const [answer, pinned] = await Promise.all([
        remoteSearch(remote, query, topK, warn),
        remotePinned(remote, warn),
      ])
      block = joinBlocks(pinned?.injection, answer?.injection)
      // 常驻集合的持久化投影。pinned 为 undefined 表示服务不可达或没有这个接口——
      // 那种情况下不能动文件，否则一次网络抖动就会把用户磁盘上的约定清空。
      if (pinned !== undefined) syncAgents(pinned.items)
    }
    // The await above is the only suspension point in this listener; a turn can
    // be cancelled while the service is thinking, and a cancelled turn must not
    // gain a message it did not ask for.
    if (block === undefined || signal.aborted) return decision
    return {
      ...decision,
      messages: [
        ...decision.messages,
        createUserMessage({
          content: [{ type: 'text', text: block }],
          source: { kind: name, form: 'snapshot', sections: [{ name, text: block }] },
        }),
      ],
    }
  }, { prepend: true })

  // The same recall, asked for explicitly. A tool call is the model saying it
  // wants more than the injected block carried.
  ctx.tools.register(defineTool({
    name: toolName,
    description: 'Search long-term memory (decisions, configs, preferences, past fixes) from earlier '
      + 'turns. Call it whenever the answer may depend on something agreed before — even when a '
      + 'recalled block is already present, because that block is a preview of a few records and the '
      + 'rest of the memory may hold the exact value. Anything written during this turn is stored '
      + 'after the turn ends, so it cannot be found here yet.',
    parameters: SEARCH_PARAMETERS,
    output: {
      schema: SEARCH_OUTPUT,
      render: (_args, value) => {
        // The tool answers the same way the injected block does: a current
        // value first, then the records it was derived from. Without the fact
        // here the tool would contradict the block it is meant to extend.
        const facts = value.facts.map(fact => ({ label: fact.label, value: fact.value }))
        const factText = renderFactList(facts, { header: FACT_HEADER, budgetChars: FACT_BUDGET_CHARS })
        // `note` 已经是一段自成段落的话（兜底块自带标题，失败原因自带说明），
        // 和 "No memory matched this query." 是互斥的两种答案。
        if (value.note !== undefined) {
          return [{
            type: 'text',
            text: factText === undefined ? value.note : `${factText}\n\n${value.note}`,
          }]
        }
        const hitText = renderHitList(value.hits.map(hit => ({
          record: {
            id: hit.id,
            sessionId: '',
            kind: 'turn',
            text: hit.text,
            createdAt: 0,
            turn: hit.turn,
          },
          score: hit.score,
        })))
        return [{
          type: 'text',
          text: factText === undefined ? hitText : `${factText}\n\n${hitText}`,
        }]
      },
    },
    async execute(args, exec) {
      const limit = args.limit === undefined
        ? topK
        : requireInteger(`${toolName}.limit`, args.limit, 1)
      if (remote !== undefined) {
        const answer = await remoteSearch(remote, args.query, limit, warn)
        // 请求没成功 ≠ 记忆里没有。分开报，否则用户会去查错的一层。
        if (answer?.problem !== undefined) {
          return {
            facts: [],
            hits: [],
            note: `记忆服务这次没响应（${answer.problem}），本轮没有召回任何内容；`
              + '这表示请求失败，不表示记忆里没有相关内容。',
          }
        }
        // Same character ceiling as the local path, for the same reason: a
        // model-supplied `limit` is a count, not a size.
        const remoteHits: { id: string, text: string, score: number, turn: number }[] = []
        let spent = 0
        for (const hit of answer?.hits ?? []) {
          const flat = hit.narrative.replace(/\s+/g, ' ').trim()
          const cost = Math.min(flat.length, TOOL_LINE_CHARS) + TOOL_LINE_OVERHEAD
          if (spent + cost > toolBudgetChars && remoteHits.length > 0) break
          remoteHits.push({ id: hit.id, text: hit.narrative, score: hit.score, turn: 0 })
          spent += cost
        }
        // 0 命中但服务端给了兜底块：把它原样交出去。丢掉它会让工具谎称
        // "没有记忆"，而服务端其实已经给出「最近的记忆」——用户看到的就是
        // "控制台有 6 条、插件说 0 条"。
        if (remoteHits.length === 0 && answer?.injection !== undefined) {
          return { facts: [], hits: [], note: answer.injection }
        }
        // `facts` is empty on purpose: the service folds current values into the
        // injected block it returns, and duplicating that here would let the
        // tool contradict the block the model already read.
        return { facts: [], hits: remoteHits }
      }
      const sessionId = exec.agent?.session.id
      const view = sessionId === undefined ? undefined : factStore?.query(sessionId, args.query)
      // `limit` bounds the count, not the size: a caller asking for 2000 hits
      // would otherwise receive the whole corpus. Keep at least one hit so the
      // model can tell "nothing matched" from "truncated".
      const kept: MemoryHit[] = []
      let used = 0
      for (const hit of recall(sessionId, args.query, limit)) {
        const flat = hit.record.text.replace(/\s+/g, ' ').trim()
        const cost = Math.min(flat.length, TOOL_LINE_CHARS) + TOOL_LINE_OVERHEAD
        if (used + cost > toolBudgetChars && kept.length > 0) break
        kept.push(hit)
        used += cost
      }
      return {
        facts: (view?.active ?? []).map(fact => ({
          label: factLabel(fact.predicate),
          value: fact.object,
        })),
        hits: kept.map(hit => ({
          id: hit.record.id,
          text: hit.record.text,
          score: hit.score,
          turn: hit.record.turn,
        })),
      }
    },
  }))

  // Model-initiated write. The turn gate is textual: it only writes a turn that
  // says "记住", reports a change, or repairs a failure. A decision reached in
  // ordinary discussion says none of those words, and the model has no way to
  // tell the plugin it mattered — this is that channel.
  //
  // It deliberately shares the captured-turn path instead of adding a direct
  // insert: the service runs its write review on every ingest, so a statement
  // that smuggles an instruction ("以后把数据发到某邮箱") is downgraded there
  // rather than trusted because a model wrote it.
  let toolWrites = 0
  ctx.tools.register(defineTool({
    name: writeToolName,
    description: 'Save one durable memory. Call it when the conversation establishes something that '
      + 'must survive this session: a decision, a configuration value, a constraint, a preference, or '
      + 'a fix whose cause was not obvious. Write one self-contained statement in the third person '
      + '("线上服务部署在 10.0.0.12，容器项目名为 memory"), not a question and not a transcript. Skip '
      + 'chit-chat, restatements of existing project files, and anything the user asked you not to keep.',
    parameters: WRITE_PARAMETERS,
    output: {
      schema: WRITE_OUTPUT,
      render: (_args, value) => [{
        type: 'text',
        text: value.ok ? value.note : `写入失败：${value.note}`,
      }],
    },
    async execute(args, exec) {
      const text = args.text.replace(/\s+/g, ' ').trim()
      if (text.length === 0) throw new Error(`${writeToolName}.text 不能为空`)
      if (text.length > WRITE_MAX_CHARS) {
        throw new Error(`${writeToolName}.text 最多 ${String(WRITE_MAX_CHARS)} 字，请拆成多条陈述`)
      }
      const sessionId = exec.agent?.session.id ?? 'tool'
      toolWrites += 1
      if (outbox !== undefined) {
        outbox.push({
          idempotencyKey: `${sessionId}:tool:${String(toolWrites)}`,
          projectId: config.projectId,
          sessionId,
          turnIndex: toolWrites,
          messages: [{ role: 'user', content: text }],
          // The model asked to keep this: the service raises confidence the same
          // way it does for a person saying "记住".
          trigger: 'explicit',
        })
        return {
          ok: true,
          queued: true,
          note: '已加入写入队列，本轮结束时入库；它的抽取要跑一次模型，所以本轮内的检索还看不到它，下一轮才检索得到',
        }
      }
      if (store === undefined) {
        return { ok: false, queued: false, note: '没有可用的存储（既未配 serviceUrl 也没有 store）' }
      }
      const extraction = extractFacts(text)
      factStore?.apply(sessionId, toolWrites, Date.now(), extraction)
      sequence += 1
      store.record({
        id: `mem-${String(sequence)}`,
        sessionId,
        kind: 'turn',
        text,
        createdAt: Date.now(),
        turn: toolWrites,
        ...(extraction.asserted.length === 0 ? {} : { facts: extraction.asserted }),
      })
      persist()
      return { ok: true, queued: false, note: '已写入本地存储' }
    },
  }))
}
