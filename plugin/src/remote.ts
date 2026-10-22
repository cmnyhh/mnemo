/**
 * Thin-client transport to a standalone memory service.
 *
 * The plugin owns no retrieval logic in this mode: it captures the turn, posts
 * it, and renders whatever comes back. Two consequences shape the code here.
 *
 * **Recall must never fail a turn.** A pre-step that throws takes the whole
 * request down, so every failure path returns `undefined` and the caller simply
 * injects nothing. "No memory this turn" is a degraded answer; "no turn" is not.
 *
 * **Write must survive a restart of the service.** A captured turn is the only
 * copy of something a person said, so a failed post is queued and retried with
 * the same idempotency key — the service deduplicates on it, which makes a
 * retry after a timeout safe rather than a duplicate record.
 *
 * @module @deepseek-ai/dsh-memory-core/remote
 */

/** Where the service lives and how to authenticate to it. */
export interface RemoteOptions {
  /** Base URL without a trailing slash, e.g. `http://127.0.0.1:8090`. */
  readonly baseUrl: string
  /**
   * Bearer token reader; the service derives the tenant from it, never from the
   * body. A function because the token is a volatile config field: the settings
   * panel can replace it while the plugin runs, and a snapshot taken at apply
   * time would keep sending the old one until a restart.
   */
  readonly token: () => string
  /** Project the records belong to; `undefined` means tenant-wide. */
  readonly projectId: string | undefined
  /** Hard ceiling for one **read** (recall / tool search). Tighter than the caller's. */
  readonly timeoutMs: number
  /**
   * Hard ceiling for one **write** (`/ingest`).
   *
   * Writing is not as cheap as reading: the service runs an extraction model
   * before it answers, which takes 0.4–7 s. Reusing the read ceiling here made
   * every write abort client-side at 1.2 s (nginx logged 499) while the server
   * carried on and stored the turn anyway — so the queue never saw a success and
   * kept retrying work that had already been done.
   */
  readonly writeTimeoutMs?: number
}

/** One recalled record, already scored by the service. */
export interface RemoteHit {
  readonly id: string
  readonly narrative: string
  readonly kind: string
  readonly score: number
  readonly bm25Rank: number | null
  readonly denseRank: number | null
}

/** One recall answer. `injection` is the ready-to-send block, if the service built one. */
export interface RemoteAnswer {
  readonly route: string
  readonly abstained: boolean
  readonly injection: string | undefined
  readonly hits: readonly RemoteHit[]
  readonly degradeReason: string | undefined
  /**
   * Set when the call did not produce an answer at all (HTTP error, timeout,
   * bad body). Distinguishes "the service had nothing" from "the service was
   * never reached": a caller that prints the same thing for both is lying to
   * the user, and the user then debugs the wrong layer.
   */
  readonly problem?: string
}

/** One item the client has already decided to keep; the service stores it as-is. */
export interface IngestItem {
  readonly kind: string
  readonly narrative: string
  readonly scope?: string
  readonly confidence?: number
  readonly turn?: number
  readonly sourceKind?: string
}

/** One turn to remember. The idempotency key is what makes a retry safe. */
export interface IngestPayload {
  readonly idempotencyKey: string
  readonly projectId: string | undefined
  readonly sessionId: string
  readonly turnIndex: number
  readonly messages: readonly { readonly role: string; readonly content: string }[]
  /**
   * Why the client decided this turn was worth writing (`explicit` / `change` /
   * `repair`). The service uses it to raise confidence and trust for an explicit
   * "remember this" — the person authorised it, so it is not a model inference.
   */
  readonly trigger?: string
  /**
   * Items to store verbatim, bypassing the service's extraction model.
   *
   * <p>Set by `memory_write`: the model already decided what to keep and phrased
   * it as one self-contained statement. Sending it as a bare turn would make the
   * service run the extractor a second time — 0.4–7 s slower, and it can reword
   * the statement or drop it under `extract.min-confidence`. The turn is still
   * attached for `trigger: 'explicit'`, which is what waives the admission check.
   */
  readonly items?: readonly IngestItem[]
}

/** Sink for warnings; the plugin's logger, narrowed so this module stays host-free. */
export type Warn = (message: string, error?: unknown) => void

/** One pinned record, structured — the AGENTS.md projection needs the text, not the block. */
export interface RemotePinnedItem {
  readonly id: string
  readonly kind: string
  readonly tier: string
  readonly narrative: string
  readonly importance: number
}

/** Records the user pinned to be present in every turn, independent of the query. */
export interface RemotePinned {
  readonly count: number
  readonly injection: string | undefined
  /**
   * Structured form of the same records. Older services return only
   * `injection`, so this is `[]` there and every caller must tolerate it.
   */
  readonly items: readonly RemotePinnedItem[]
}

function parsePinnedItems(raw: unknown): RemotePinnedItem[] {
  const list = Array.isArray(raw) ? raw : []
  const items: RemotePinnedItem[] = []
  for (const entry of list) {
    const item = asRecord(entry)
    if (item === undefined) continue
    const id = typeof item.id === 'string' ? item.id : undefined
    const narrative = typeof item.narrative === 'string' ? item.narrative : undefined
    if (id === undefined || narrative === undefined || narrative.trim().length === 0) continue
    items.push({
      id,
      narrative,
      kind: typeof item.kind === 'string' ? item.kind : 'episode',
      tier: typeof item.tier === 'string' ? item.tier : 'T0',
      importance: asNumber(item.importance) ?? 0,
    })
  }
  return items
}

/**
 * Pinned block cache.
 *
 * The block changes only when someone pins or unpins a record, but the pre-step
 * asks for it on every turn. One minute is short enough that a pin made in the
 * console shows up in the next turn or two, and long enough that a burst of
 * turns does not pay a round trip each time.
 */
const PINNED_TTL_MS = 60_000

let pinnedCache: { key: string, at: number, value: RemotePinned | undefined } | undefined

function joinUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : undefined
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function parseHits(raw: unknown): RemoteHit[] {
  const list = Array.isArray(raw) ? raw : []
  const hits: RemoteHit[] = []
  for (const entry of list) {
    const hit = asRecord(entry)
    if (hit === undefined) continue
    const id = typeof hit.id === 'string' ? hit.id : undefined
    const narrative = typeof hit.narrative === 'string' ? hit.narrative : undefined
    if (id === undefined || narrative === undefined) continue
    hits.push({
      id,
      narrative,
      kind: typeof hit.kind === 'string' ? hit.kind : 'unknown',
      score: asNumber(hit.score) ?? 0,
      bm25Rank: asNumber(hit.bm25Rank),
      denseRank: asNumber(hit.denseRank),
    })
  }
  return hits
}

/**
 * Fetch the pinned block (T0). Unlike recall this is **not** query-dependent:
 * a pinned record is injected even when the user never mentions it.
 *
 * @returns the block, or `undefined` when nothing is pinned, the service is
 *   unreachable, or the answer is empty. Callers treat `undefined` as
 *   "inject nothing extra"; a missing pinned block must not fail a turn.
 */
export async function remotePinned(
  options: RemoteOptions,
  warn: Warn,
): Promise<RemotePinned | undefined> {
  // 未配置令牌时不要发请求：那只会得到一串 401，把「还没填令牌」伪装成
  // 「令牌错了」，而且每轮都刷一遍日志。
  if (options.token().length === 0) return undefined
  const key = `${options.baseUrl}|${options.token()}`
  if (pinnedCache !== undefined && pinnedCache.key === key
      && Date.now() - pinnedCache.at < PINNED_TTL_MS) {
    return pinnedCache.value
  }
  let value: RemotePinned | undefined
  try {
    const response = await fetch(joinUrl(options.baseUrl, '/v1/memory/pinned'), {
      headers: { authorization: `Bearer ${options.token()}` },
      signal: AbortSignal.timeout(options.timeoutMs),
    })
    if (response.ok) {
      const payload = asRecord(await response.json())
      if (payload !== undefined) {
        const injection = typeof payload.injection === 'string' && payload.injection.trim().length > 0
          ? payload.injection
          : undefined
        value = {
          count: asNumber(payload.count) ?? 0,
          injection,
          items: parsePinnedItems(payload.items),
        }
      }
    } else {
      // 404 means an older service without the endpoint: stay quiet, it is not
      // a fault the user can act on.
      if (response.status !== 404) {
        warn(`memory-core: pinned returned HTTP ${String(response.status)}; injecting none`)
      }
    }
  } catch (error) {
    warn('memory-core: pinned fetch failed; injecting none', error)
  }
  pinnedCache = { key, at: Date.now(), value }
  return value
}

/**
 * Ask the service what it remembers about `query`.
 *
 * @returns the answer, or `undefined` when the service is unreachable, slow, or
 *   answered with something that is not an answer. Callers treat `undefined` as
 *   "inject nothing".
 */
export async function remoteSearch(
  options: RemoteOptions,
  query: string,
  limit: number,
  warn: Warn,
): Promise<RemoteAnswer | undefined> {
  if (options.token().length === 0) return undefined
  const body: Record<string, unknown> = { query, topK: limit }
  if (options.projectId !== undefined) body.projectId = options.projectId
  try {
    const response = await fetch(joinUrl(options.baseUrl, '/v1/memory/search'), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${options.token()}`,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(options.timeoutMs),
    })
    if (!response.ok) {
      // 401 means the token is wrong: that is a configuration fault, and it
      // repeats on every turn, so it is worth saying out loud once per call.
      warn(`memory-core: search returned HTTP ${String(response.status)}; injecting nothing`)
      return fail(`HTTP ${String(response.status)}`)
    }
    const payload = asRecord(await response.json())
    if (payload === undefined) return fail('响应不是 JSON 对象')
    return {
      route: typeof payload.route === 'string' ? payload.route : 'unknown',
      abstained: payload.abstained === true,
      injection: typeof payload.injection === 'string' && payload.injection.trim().length > 0
        ? payload.injection
        : undefined,
      hits: parseHits(payload.hits),
      degradeReason: typeof payload.degradeReason === 'string' ? payload.degradeReason : undefined,
    }
  } catch (error) {
    const reason = error instanceof Error ? `${error.name}: ${error.message}` : String(error)
    warn('memory-core: search failed; injecting nothing', error)
    return fail(reason)
  }
}

/** A search that never got an answer. `hits` is empty because nothing came back. */
function fail(reason: string): RemoteAnswer {
  return {
    route: 'error',
    abstained: false,
    injection: undefined,
    hits: [],
    degradeReason: undefined,
    problem: reason,
  }
}

/**
 * Bounded write-behind queue.
 *
 * Writes are posted after the turn ends, so nothing awaits them. The queue is
 * what makes a service restart invisible to the user: the turn sits here until
 * a flush succeeds. It is bounded because an unbounded queue behind a dead
 * service is a memory leak that only shows up under load — when it is too late
 * to diagnose. On overflow the **oldest** entry is dropped: the newest turn is
 * the one a later question is most likely to be about.
 */
export class Outbox {
  private readonly queue: IngestPayload[] = []
  private flushing: Promise<void> | undefined
  private dropped = 0

  constructor(
    private readonly options: RemoteOptions,
    private readonly capacity: number,
    private readonly warn: Warn,
  ) {}

  /** Entries waiting to be delivered. */
  get size(): number {
    return this.queue.length
  }

  /** Entries discarded because the service stayed unreachable. */
  get droppedCount(): number {
    return this.dropped
  }

  /** Queue one turn and start delivering, without awaiting. */
  push(payload: IngestPayload): void {
    if (this.queue.length >= this.capacity) {
      this.queue.shift()
      this.dropped += 1
      if (this.dropped === 1 || this.dropped % 50 === 0) {
        this.warn(`memory-core: outbox full; dropped ${String(this.dropped)} oldest turn(s)`)
      }
    }
    this.queue.push(payload)
    void this.flush()
  }

  /**
   * Deliver queued turns in order, stopping at the first failure.
   *
   * Serial on purpose: the service assigns `createdAt` at write time, so posting
   * turn 5 before turn 3 would record the correction before the thing it
   * corrects. A failed flush leaves the rest queued for the next attempt.
   */
  flush(): Promise<void> {
    if (this.flushing !== undefined) return this.flushing
    this.flushing = this.drain().finally(() => {
      this.flushing = undefined
    })
    return this.flushing
  }

  private async drain(): Promise<void> {
    while (this.queue.length > 0) {
      const payload = this.queue[0]
      if (payload === undefined) return
      const delivered = await this.deliver(payload)
      if (!delivered) return
      this.queue.shift()
    }
  }

  private async deliver(payload: IngestPayload): Promise<boolean> {
    // 未配置令牌时丢弃而不是排队：队列会在补上令牌后一次性灌进去一批
    // 当时的对话，而那时的上下文早已过去。
    if (this.options.token().length === 0) return true
    const body: Record<string, unknown> = {
      idempotencyKey: payload.idempotencyKey,
      sessionId: payload.sessionId,
      turn: {
        sessionId: payload.sessionId,
        index: payload.turnIndex,
        messages: payload.messages.map(message => ({ role: message.role, content: message.content })),
        ...(payload.trigger === undefined ? {} : { trigger: payload.trigger }),
      },
    }
    if (payload.projectId !== undefined) body.projectId = payload.projectId
    // items 一旦出现，服务端就跳过抽取模型直接落库；turn 只是用来传 trigger。
    if (payload.items !== undefined && payload.items.length > 0) {
      body.items = payload.items
    }
    // 写超时独立于读超时，见 RemoteOptions.writeTimeoutMs 的说明。
    const writeTimeoutMs = this.options.writeTimeoutMs ?? Math.max(this.options.timeoutMs * 10, 15_000)
    try {
      const response = await fetch(joinUrl(this.options.baseUrl, '/v1/memory/ingest'), {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${this.options.token()}`,
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(writeTimeoutMs),
      })
      if (!response.ok) {
        // 4xx other than 429 will never succeed on retry: a malformed turn is a
        // bug, not a blip. Drop it rather than block every later turn behind it.
        if (response.status >= 400 && response.status < 500 && response.status !== 429) {
          this.warn(`memory-core: ingest rejected with HTTP ${String(response.status)}; turn dropped`)
          return true
        }
        this.warn(`memory-core: ingest failed with HTTP ${String(response.status)}; will retry`)
        return false
      }
      return true
    } catch (error) {
      this.warn('memory-core: ingest failed; will retry', error)
      return false
    }
  }
}
