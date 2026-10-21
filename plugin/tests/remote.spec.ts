import { afterEach, describe, expect, it, vi } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import { agentEvents, type Agent, type PreStepDecision } from '@deepseek-ai/dsh-agent'
import { createAssistantMessage, createUserMessage, type UserMessage } from '@deepseek-ai/dsh-llm'
import { SessionId, type Session } from '@deepseek-ai/dsh-session'
import { mountAgentLoopTestDependencies, unsupportedInbox } from '@deepseek-ai/dsh-agent-loop-testkit'
import * as memoryCore from '../src/index.ts'
import { Outbox, remoteSearch } from '../src/remote.ts'
import type { RemoteOptions } from '../src/remote.ts'

const SIGNAL = new AbortController().signal
const noWarn = (): void => {}

const OPTIONS: RemoteOptions = {
  baseUrl: 'http://memory.test:8090',
  token: () => 'secret',
  projectId: 'kb-service',
  timeoutMs: 500,
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/** A fetch stub that answers from a queue and records what it was asked. */
function stubFetch(answers: { status?: number, body: unknown }[]) {
  const calls: { url: string, body: Record<string, unknown>, auth: string | null }[] = []
  const queue = [...answers]
  const stub = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const raw = typeof init?.body === 'string' ? init.body : '{}'
    const headers = new Headers(init?.headers)
    calls.push({ url, body: JSON.parse(raw) as Record<string, unknown>, auth: headers.get('authorization') })
    const next = queue.shift()
    if (next === undefined) throw new Error('unexpected extra request')
    const status = next.status ?? 200
    return new Response(JSON.stringify(next.body), {
      status,
      headers: { 'content-type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', stub)
  return { calls, stub }
}

describe('remote search', () => {
  it('parses the answer and sends the project scope', async () => {
    const { calls } = stubFetch([{
      body: {
        route: 'hybrid',
        abstained: false,
        count: 2,
        hits: [
          { id: 'a', narrative: '端口是 8080', kind: 'fact', score: 0.9, bm25Rank: 1, denseRank: 1 },
          { id: 'b', narrative: '另一条', kind: 'episode', score: 0.4, bm25Rank: 3, denseRank: null },
        ],
        injection: '## 相关记忆',
      },
    }])

    const answer = await remoteSearch(OPTIONS, '端口多少', 6, noWarn)

    expect(calls[0]?.url).toBe('http://memory.test:8090/v1/memory/search')
    expect(calls[0]?.body).toEqual({ query: '端口多少', topK: 6, projectId: 'kb-service' })
    expect(calls[0]?.auth).toBe('Bearer secret')
    expect(answer?.injection).toBe('## 相关记忆')
    expect(answer?.hits.map(hit => hit.id)).toEqual(['a', 'b'])
    // A null rank stays null: `0` would read as "ranked first" to any caller
    // that does not distinguish absent from zero.
    expect(answer?.hits[1]?.denseRank).toBeNull()
  })

  it('omits projectId when the plugin is not scoped to one', async () => {
    const { calls } = stubFetch([{ body: { route: 'none', abstained: true, hits: [] } }])
    await remoteSearch({ ...OPTIONS, projectId: undefined }, '随便', 6, noWarn)
    expect(calls[0]?.body).not.toHaveProperty('projectId')
  })

  it('reports a server error instead of pretending nothing matched', async () => {
    stubFetch([{ status: 500, body: { error: 'boom' } }])
    const answer = await remoteSearch(OPTIONS, '端口', 6, noWarn)
    // 有 problem 才让上层的工具能说"请求失败"，而不是"没有相关记忆"。
    expect(answer?.problem).toBe('HTTP 500')
    expect(answer?.injection).toBeUndefined()
    expect(answer?.hits).toEqual([])
  })

  it('reports a throw or timeout the same way', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('The operation was aborted'))))
    const answer = await remoteSearch(OPTIONS, '端口', 6, noWarn)
    expect(answer?.problem).toContain('The operation was aborted')
    expect(answer?.hits).toEqual([])
  })

  it('treats an empty injection as no block, not as an empty block', async () => {
    stubFetch([{ body: { route: 'none', abstained: true, hits: [], injection: '   ' } }])
    expect((await remoteSearch(OPTIONS, '端口', 6, noWarn))?.injection).toBeUndefined()
  })

  it('skips hits that do not carry an id and narrative', async () => {
    stubFetch([{ body: { route: 'hybrid', hits: [{ id: 'a' }, { narrative: 'b' }, { id: 'c', narrative: 'ok' }] } }])
    const answer = await remoteSearch(OPTIONS, '端口', 6, noWarn)
    expect(answer?.hits.map(hit => hit.id)).toEqual(['c'])
  })
})

const PAYLOAD = {
  idempotencyKey: 's1:3:0',
  projectId: 'kb-service',
  sessionId: 's1',
  turnIndex: 3,
  messages: [{ role: 'user', content: '端口改成 9090' }],
}

describe('outbox', () => {
  it('delivers one turn with its idempotency key', async () => {
    const { calls } = stubFetch([{ body: { accepted: 1 } }])
    const outbox = new Outbox(OPTIONS, 10, noWarn)

    outbox.push(PAYLOAD)
    await outbox.flush()

    expect(outbox.size).toBe(0)
    expect(calls[0]?.url).toBe('http://memory.test:8090/v1/memory/ingest')
    expect(calls[0]?.body.idempotencyKey).toBe('s1:3:0')
    expect(calls[0]?.body.turn).toEqual({
      sessionId: 's1',
      index: 3,
      messages: [{ role: 'user', content: '端口改成 9090' }],
    })
  })

  it('waits longer for a write than for a read, because ingest runs an extraction model', async () => {
    // 回归：写请求曾复用读超时（1200 ms），而服务端抽取要 0.4–7 s，于是每次
    // 写入都在客户端先断（nginx 记 499），队列以为失败并保留重发。
    vi.stubGlobal('fetch', vi.fn(async () => {
      await new Promise(resolve => { setTimeout(resolve, 120) })
      return new Response(JSON.stringify({ accepted: 1 }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))
    const outbox = new Outbox({ ...OPTIONS, timeoutMs: 20, writeTimeoutMs: 2000 }, 10, noWarn)

    outbox.push(PAYLOAD)
    await outbox.flush()

    expect(outbox.size).toBe(0)
  })

  it('keeps the turn queued when the service is down, and retries the same key', async () => {
    stubFetch([{ status: 503, body: {} }, { body: { accepted: 1 } }])
    const outbox = new Outbox(OPTIONS, 10, noWarn)

    outbox.push(PAYLOAD)
    await outbox.flush()
    expect(outbox.size).toBe(1)

    await outbox.flush()
    expect(outbox.size).toBe(0)
    expect(outbox.droppedCount).toBe(0)
  })

  it('drops a turn the service rejects with 4xx instead of blocking the queue', async () => {
    const { calls } = stubFetch([{ status: 400, body: { error: 'bad' } }, { body: { accepted: 1 } }])
    const outbox = new Outbox(OPTIONS, 10, noWarn)

    outbox.push(PAYLOAD)
    await outbox.flush()
    expect(outbox.size).toBe(0)

    outbox.push({ ...PAYLOAD, idempotencyKey: 's1:4:0' })
    await outbox.flush()
    expect(calls).toHaveLength(2)
  })

  it('delivers in order, so a later correction is never written before what it corrects', async () => {
    const seen: string[] = []
    let failedOnce = false
    vi.stubGlobal('fetch', vi.fn(async (_input: unknown, init?: RequestInit) => {
      const key = String((JSON.parse(String(init?.body)) as { idempotencyKey: string }).idempotencyKey)
      if (key === 't2' && !failedOnce) {
        failedOnce = true
        seen.push(`${key}!`)
        return new Response('{}', { status: 503 })
      }
      seen.push(key)
      return new Response('{}')
    }))
    const outbox = new Outbox(OPTIONS, 10, noWarn)

    outbox.push({ ...PAYLOAD, idempotencyKey: 't1' })
    await outbox.flush()
    outbox.push({ ...PAYLOAD, idempotencyKey: 't2' })
    await outbox.flush()
    expect(outbox.size).toBe(1)
    // t3 arrives while t2 is still queued: t2 must go first, or the service
    // records turn 4 before turn 3 and "later wins" picks the wrong value.
    outbox.push({ ...PAYLOAD, idempotencyKey: 't3' })
    await outbox.flush()

    expect(seen).toEqual(['t1', 't2!', 't2', 't3'])
    expect(outbox.size).toBe(0)
  })

  it('drops the oldest turn when the queue is full', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('down'))))
    const outbox = new Outbox(OPTIONS, 2, noWarn)

    for (const key of ['a', 'b', 'c']) {
      outbox.push({ ...PAYLOAD, idempotencyKey: key })
      await outbox.flush()
    }

    expect(outbox.size).toBe(2)
    expect(outbox.droppedCount).toBe(1)
  })
})

/** A stub Agent over one store-owned Session: enough for the pre-step waterfall. */
function sessionAgent(session: Session, id = 'agent'): Agent {
  return {
    id: SessionId(id),
    options: {},
    session,
    inbox: unsupportedInbox(),
    status: 'running',
    ctx: new Context(),
    send: () => {},
    followup: () => {},
    steer: () => {},
    inject: () => { throw new Error('memory-core must return context from pre-step') },
    cancel() {},
    runMaintenance: task => task(new AbortController().signal),
    whenIdle: () => Promise.resolve(),
  }
}

async function mount(config: memoryCore.Config = {}): Promise<Context> {
  const ctx = new Context()
  await mountAgentLoopTestDependencies(ctx)
  await ctx.plugin(memoryCore, config)
  return ctx
}

function userMessage(text: string): UserMessage {
  return createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } })
}

function recordTurn(session: Session, turn: number, prompt: string, reply: string): void {
  session.append('turn/start', { turn })
  session.append('user/message', userMessage(prompt), { surfaceOp: 'append' })
  session.append('assistant/message', {
    turn,
    step: 1,
    message: createAssistantMessage({
      content: [{ type: 'text', text: reply }],
      source: { provider: 'mock', model: 'mock' },
    }),
    stream: [],
  }, { surfaceOp: 'append' })
  session.append('turn/end', { turn, reason: { kind: 'completed' } })
}

async function fire(
  ctx: Context,
  agent: Agent,
  turn: number,
  step: number,
  prompt: string,
): Promise<{ decision: PreStepDecision; injected?: string }> {
  const proposed = userMessage(prompt)
  const decision = await agentEvents(ctx, agent).waterfall(
    'agent/pre-step',
    { messages: [proposed], turn, step, signal: SIGNAL },
    () => Promise.resolve({ kind: 'enter' as const, messages: [proposed] }),
  )
  if (decision.kind === 'reject') return { decision }
  const block = decision.messages
    .filter(message => message.source.kind === memoryCore.name)
    .flatMap(message => message.content.filter(content => content.type === 'text'))
    .map(content => content.text)
    .join('\n')
  return block.length === 0 ? { decision } : { decision, injected: block }
}

const REMOTE_CONFIG = { serviceUrl: 'http://memory.test:8090', serviceToken: 'secret', projectId: 'kb-service' }

describe('thin client', () => {
  it('injects the block the service rendered, verbatim', async () => {
    stubFetch([{ body: { route: 'hybrid', abstained: false, hits: [], injection: '## 相关记忆\n- [fact] 端口 8080' } }])
    const ctx = await mount(REMOTE_CONFIG)
    const session = ctx.sessions.create(SessionId('thin'))

    const { injected } = await fire(ctx, sessionAgent(session), 1, 1, '端口是多少')

    expect(injected).toBe('## 相关记忆\n- [fact] 端口 8080')
  })

  it('injects nothing when the service fails, and does not fall back to a stale local index', async () => {
    stubFetch([{ status: 500, body: {} }])
    const ctx = await mount(REMOTE_CONFIG)
    const session = ctx.sessions.create(SessionId('down'))

    const { injected } = await fire(ctx, sessionAgent(session), 1, 1, '端口是多少')

    expect(injected).toBeUndefined()
  })

  it('posts the finished turn with a stable idempotency key', async () => {
    const { calls } = stubFetch([{ body: { accepted: 1 } }])
    // 这条用例验的是传输层：幂等键、projectId、报文形状。
    // 闸门默认会拦下这类短轮次，所以这里显式关掉，让断言只关于传输。
    const ctx = await mount({ ...REMOTE_CONFIG, writeGate: false })
    const session = ctx.sessions.create(SessionId('post'))
    recordTurn(session, 7, '端口改成 9090', '已记录')

    await vi.waitFor(() => {
      expect(calls.some(call => call.url.endsWith('/v1/memory/ingest'))).toBe(true)
    })
    const ingest = calls.find(call => call.url.endsWith('/v1/memory/ingest'))
    expect(ingest?.body.idempotencyKey).toBe('post:7:0')
    expect(ingest?.body.projectId).toBe('kb-service')
    expect(ingest?.body.turn).toEqual({
      sessionId: 'post',
      index: 7,
      messages: [
        { role: 'user', content: '端口改成 9090' },
        { role: 'assistant', content: '已记录' },
      ],
    })
  })

  it('loads without a token and stays silent instead of 401ing on every turn', async () => {
    // 这里曾经要求加载期抛错。加了设置面板之后就错了：面板只能编辑"活动"条目，
    // 抛错会让条目永远不活动，于是唯一能补令牌的界面也出不来。现在要求是：
    // 挂得上、不发请求、等面板写入后按下一次请求生效。
    const ctx = await mount({ serviceUrl: 'http://memory.test:8090' })
    expect(ctx).toBeDefined()

    const { stub } = stubFetch([])
    const answer = await remoteSearch({ ...OPTIONS, token: () => '' }, '部署端口', 5, noWarn)
    expect(answer).toBeUndefined()
    expect(stub).not.toHaveBeenCalled()
  })

  it('drops queued turns while unconfigured instead of flushing them later', async () => {
    const { stub } = stubFetch([])
    const outbox = new Outbox({ ...OPTIONS, token: () => '' }, 10, noWarn)
    outbox.push({
      idempotencyKey: 'k1',
      sessionId: 's1',
      turnIndex: 1,
      messages: [{ role: 'user', content: '你好' }],
      projectId: undefined,
    })
    await outbox.flush()
    expect(outbox.size).toBe(0)
    expect(stub).not.toHaveBeenCalled()
  })
})
