import { afterEach, describe, expect, it, vi } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import { agentEvents, type Agent, type PreStepDecision } from '@deepseek-ai/dsh-agent'
import { createAssistantMessage, createUserMessage, type UserMessage } from '@deepseek-ai/dsh-llm'
import { SessionId, type Session } from '@deepseek-ai/dsh-session'
import type { ToolRunContext } from '@deepseek-ai/dsh-tools'
import { mountAgentLoopTestDependencies, unsupportedInbox } from '@deepseek-ai/dsh-agent-loop-testkit'
import * as memoryCore from '../src/index.ts'

const SIGNAL = new AbortController().signal

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

/** Mount the plugin over the real prerequisite services and ToolRuntime. */
async function mount(config: memoryCore.Config = {}): Promise<Context> {
  const ctx = new Context()
  await mountAgentLoopTestDependencies(ctx)
  // 本文件检验捕获与召回本身；多数用例用一句话完成一轮，会被“无信号短轮”闸门
  // 正常滤掉。闸门行为由 gate.spec.ts 与本文件末尾的集成用例覆盖，这里默认关闭。
  await ctx.plugin(memoryCore, { writeGate: false, ...config })
  return ctx
}

function userMessage(text: string): UserMessage {
  return createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } })
}

/** Append one complete turn through the store so `session/event` observes it. */
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

/** Run one pre-step waterfall and return the decision plus the injected block, if any. */
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

describe('capture and recall', () => {
  it('recalls a finished turn into the next turn first step', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('recall'))
    recordTurn(session, 1, '记忆库决定用 PostgreSQL 18，不用 MySQL。', '已记录，用 PostgreSQL 18。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '记忆库用 PostgreSQL 还是 MySQL？')

    expect(injected).toBeDefined()
    expect(injected).toContain('PostgreSQL 18')
    expect(injected).toContain('Recalled long-term memory')
  })

  it('misses a paraphrase that shares no terms, and therefore abstains', async () => {
    // Pinned M0 behavior, not desired behavior: recall is lexical, so a
    // semantically equivalent question with no shared term stays out. The
    // embedding half of the M1 hybrid index is what turns this into a hit; until
    // then the floor keeps the block silent rather than injecting a wrong record.
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('paraphrase'))
    recordTurn(session, 1, '记忆库决定用 PostgreSQL 18，不用 MySQL。', '已记录。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '数据库选型是什么？')

    expect(injected).toBeUndefined()
  })

  it('injects at the turn-opening step only', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('once'))
    recordTurn(session, 1, '部署端口固定为 8080。', '好。')
    const agent = sessionAgent(session)

    const first = await fire(ctx, agent, 2, 1, '部署端口是多少？')
    const laterStep = await fire(ctx, agent, 2, 2, '部署端口是多少？')
    const retriedFirstStep = await fire(ctx, agent, 2, 1, '部署端口是多少？')

    expect(first.injected).toContain('8080')
    expect(laterStep.injected).toBeUndefined()
    // A retried preparation re-injects: the attempt that failed is the attempt
    // that needed the block, so the guard is the step number, not a turn counter.
    expect(retriedFirstStep.injected).toContain('8080')
  })

  it('abstains when nothing clears the coverage floor', async () => {
    const ctx = await mount({ minScore: 0.9 })
    const session = ctx.sessions.create(SessionId('abstain'))
    recordTurn(session, 1, '配色方案偏冷，用蓝灰。', '记住了。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, 'Kubernetes 的 ingress 怎么配？')

    expect(injected).toBeUndefined()
  })

  it('omits the block when the budget cannot hold the header and one record', async () => {
    const ctx = await mount({ contextBudgetChars: 40 })
    const session = ctx.sessions.create(SessionId('budget'))
    recordTurn(session, 1, 'PostgreSQL 18 是记忆库。', '好。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '数据库是什么？')

    expect(injected).toBeUndefined()
  })

  it('does not treat plugin context as a recallable prompt', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('plugin'))
    session.append('turn/start', { turn: 1 })
    session.append('user/message', createUserMessage({
      content: [{ type: 'text', text: 'scheduled maintenance ran' }],
      source: { kind: 'schedule' } as never,
    }), { surfaceOp: 'append' })
    session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, 'scheduled maintenance ran')

    expect(injected).toBeUndefined()
  })
})

describe('record granularity', () => {
  const FILLER = '这一段讲的是迁移的历史背景与当时的取舍，以及后来为什么没有再改动，和端口本身没有关系。'

  /** Append one assistant message to the current turn. */
  function say(session: Session, turn: number, text: string): void {
    session.append('assistant/message', {
      turn,
      step: 1,
      message: createAssistantMessage({
        content: [{ type: 'text', text }],
        source: { provider: 'mock', model: 'mock' },
      }),
      stream: [],
    }, { surfaceOp: 'append' })
  }

  it('injects the paragraph that answers, with the question beside it', async () => {
    // One record per turn used to bury this answer in a paragraph of filler:
    // the excerpt was 240 characters of context and the fact sat past the cut.
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('segment'))
    // Over the default 240-character segment ceiling, so the turn is parsed into
    // paragraphs instead of stored as one blob.
    const reply = [FILLER, FILLER, FILLER, FILLER, FILLER,
      '生产环境端口固定为 8080，改动要走变更流程。', '最后再确认一次上面的结论。'].join('\n\n')
    recordTurn(session, 1, '生产环境端口是多少？', reply)

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '生产环境端口是多少？')

    expect(injected).toContain('8080')
    expect(injected).toContain('[问: 生产环境端口是多少？]')
    expect(injected).not.toContain('历史背景')
  })

  it('never records mid-turn narration', async () => {
    // "我先查一下配置文件" precedes a tool call; it is not a fact, and storing it
    // would answer a later question about the config file with a promise.
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('narration'))
    session.append('turn/start', { turn: 1 })
    session.append('user/message', userMessage('生产环境端口是多少？'), { surfaceOp: 'append' })
    say(session, 1, '我先查一下配置文件。')
    say(session, 1, '生产环境端口固定为 8080。')
    session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '配置文件放在哪？')

    expect(injected).toBeUndefined()
  })

  it('records a steering message as its own memory', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('steering'))
    session.append('turn/start', { turn: 1 })
    session.append('user/message', userMessage('端口是多少？'), { surfaceOp: 'append' })
    say(session, 1, '8080。')
    session.append('user/message', userMessage('端口能改吗？'), { surfaceOp: 'append' })
    say(session, 1, '不能。')
    session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

    interface Hits { hits: { text: string; turn: number }[] }
    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: '端口' }, { signal: SIGNAL } as ToolRunContext) as Hits

    expect(value.hits).toHaveLength(2)
    expect(value.hits.map(hit => hit.text).join('\n')).toContain('能改吗')
  })
})

describe('memory_search', () => {
  interface SearchValue {
    hits: { id: string; text: string; score: number; turn: number }[]
  }

  it('serves the same store through the registered tool', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('tool'))
    recordTurn(session, 1, '记忆库决定用 PostgreSQL 18。', '已记录。')

    const tool = ctx.tools.get('memory_search')
    expect(tool).toBeDefined()
    const value = await tool?.execute({ query: 'PostgreSQL' }, { signal: SIGNAL } as ToolRunContext) as SearchValue

    expect(value.hits).toHaveLength(1)
    expect(value.hits[0]?.text).toContain('PostgreSQL 18')
    expect(value.hits[0]?.turn).toBe(1)
  })

  it('honors the caller limit and the shared floor', async () => {
    const ctx = await mount({ minScore: 0.9 })
    const session = ctx.sessions.create(SessionId('tool-miss'))
    recordTurn(session, 1, '记忆库决定用 PostgreSQL 18。', '已记录。')

    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: 'Redis 集群' }, { signal: SIGNAL } as ToolRunContext) as SearchValue

    expect(value.hits).toEqual([])
  })

  it('truncates a large result to the character budget, not just the count', async () => {
    // The tool accepted any `limit`, so a caller asking for everything could
    // pour the whole corpus into one turn. `limit` caps the count; the budget
    // caps the size, and one hit is always kept so "empty" stays distinguishable
    // from "truncated".
    const ctx = await mount({ toolBudgetChars: 300 })
    const session = ctx.sessions.create(SessionId('tool-budget'))
    for (let turn = 1; turn <= 20; turn += 1) recordTurn(session, turn, `端口记录 ${String(turn)}`, '好。')

    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: '端口', limit: 20 }, { signal: SIGNAL } as ToolRunContext) as SearchValue

    expect(value.hits.length).toBeGreaterThan(0)
    expect(value.hits.length).toBeLessThan(20)
  })

  it('returns every hit when the budget is not reached', async () => {
    const ctx = await mount({ toolBudgetChars: 4000 })
    const session = ctx.sessions.create(SessionId('tool-roomy'))
    for (let turn = 1; turn <= 3; turn += 1) recordTurn(session, turn, `端口记录 ${String(turn)}`, '好。')

    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: '端口', limit: 20 }, { signal: SIGNAL } as ToolRunContext) as SearchValue

    expect(value.hits).toHaveLength(3)
  })
})

/**
 * 远端模式下 `memory_search` 的两种"零命中"必须区别对待：一种是服务端给了
 * 兜底块（有内容可看），一种是请求根本没成功（该去查网络/令牌）。
 * 之前两者都渲染成 "No memory matched this query."，于是"控制台有 6 条、
 * 插件说 0 条"这种对不上的现象无法自证是哪一层的问题。
 */
describe('memory_search through the service', () => {
  const remote = { serviceUrl: 'http://memory.test:8090', serviceToken: 'secret' }

  function answerWith(body: unknown, status = 200): void {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': 'application/json' },
    })))
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('hands the fallback block to the model when nothing matched', async () => {
    const injection = '<memory_context>\n## 最近的记忆（未命中直接相关，仅供参考）\n- [fact·T1] 端口 8090\n</memory_context>'
    answerWith({ route: 'none', abstained: true, count: 0, hits: [], injection })
    const ctx = await mount(remote)

    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: 'Qdrant 向量数据库 用途' }, { signal: SIGNAL } as ToolRunContext) as
      { hits: unknown[]; note?: string }

    expect(value.hits).toEqual([])
    expect(value.note).toBe(injection)
  })

  it('says the request failed instead of claiming memory is empty', async () => {
    answerWith({ error: 'boom' }, 500)
    const ctx = await mount(remote)

    const tool = ctx.tools.get('memory_search')
    const value = await tool?.execute({ query: '端口' }, { signal: SIGNAL } as ToolRunContext) as
      { hits: unknown[]; note?: string }

    expect(value.hits).toEqual([])
    expect(value.note).toContain('HTTP 500')
    expect(value.note).toContain('请求失败')
  })
})

describe('facts through the plugin', () => {
  it('injects the current value after a correction, not both values', async () => {
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('fact-correction'))
    // The first prompt repeats the later query word for word so the record is
    // recalled on score alone: the filter, not the lexical floor, must drop it.
    recordTurn(session, 1, '生产环境端口是多少？生产环境端口固定为 8080，改动要走变更流程。', '好的。')
    recordTurn(session, 2, '端口改成 9090 了，8080 不用了', '已更新为 9090。')

    const { injected } = await fire(ctx, sessionAgent(session), 3, 1, '生产环境端口是多少？')

    expect(injected).toContain('Current facts')
    expect(injected).toContain('端口 = 9090')
    expect(injected).not.toContain('变更流程')
    expect(injected).not.toContain('端口 = 8080')
  })

  it('serves the current value through the tool and filters the stale record', async () => {
    interface SearchValue {
      facts: { label: string; value: string }[]
      hits: { text: string }[]
    }
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('fact-tool'))
    recordTurn(session, 1, '部署端口固定为 8080。', '好的。')
    recordTurn(session, 2, '端口改成 9090 了，8080 不用了', '已更新。')

    const tool = ctx.tools.get('memory_search')
    // A tool call carries its agent, and the fact view is scoped by that agent's
    // session; without it the store would have to guess which session asked.
    const value = await tool?.execute(
      { query: '端口是多少' },
      { signal: SIGNAL, agent: sessionAgent(session) } as ToolRunContext,
    ) as SearchValue

    expect(value.facts).toEqual([{ label: '端口', value: '9090' }])
    expect(value.hits.map(hit => hit.text).join('\n')).not.toContain('8080')
  })

  it('leaves the lexical layer alone when the fact layer is disabled', async () => {
    const ctx = await mount({ facts: false })
    const session = ctx.sessions.create(SessionId('fact-off'))
    recordTurn(session, 1, '部署端口固定为 8080。', '好的。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '端口是多少？')

    expect(injected).toContain('8080')
    expect(injected).not.toContain('Current facts')
  })

  it('answers each session from its own facts', async () => {
    // A fact block is labeled authoritative. An unscoped read does not add
    // background — it states another project's value as the current one.
    const ctx = await mount()
    const projectA = ctx.sessions.create(SessionId('project-a'))
    const projectB = ctx.sessions.create(SessionId('project-b'))
    recordTurn(projectA, 1, '部署端口固定为 8080。', '好的。')
    recordTurn(projectB, 1, '部署端口固定为 9090。', '好的。')

    const fromA = await fire(ctx, sessionAgent(projectA), 2, 1, '部署端口是多少？')
    const fromB = await fire(ctx, sessionAgent(projectB), 2, 1, '部署端口是多少？')

    expect(fromA.injected).toContain('端口 = 8080')
    expect(fromA.injected).not.toContain('端口 = 9090')
    expect(fromB.injected).toContain('端口 = 9090')
    expect(fromB.injected).not.toContain('端口 = 8080')
  })

  it('does not treat another session value as a supersession of its own', async () => {
    // The second failure mode: a foreign fact both displaces the correct value
    // and marks the record that states it as stale, so filtering removes the
    // only answer the session had.
    const ctx = await mount()
    const projectA = ctx.sessions.create(SessionId('scope-a'))
    const projectB = ctx.sessions.create(SessionId('scope-b'))
    recordTurn(projectA, 1, '部署端口固定为 8080，改动要走变更流程。', '好的。')
    recordTurn(projectB, 1, '端口改成 9090 了，8080 不用了', '已更新。')

    const { injected } = await fire(ctx, sessionAgent(projectA), 2, 1, '部署端口是多少？')

    expect(injected).toContain('端口 = 8080')
    expect(injected).toContain('变更流程')
  })
})

describe('configuration', () => {
  it('rejects bounds it cannot honor at plugin load', async () => {
    const zero = new Context()
    await mountAgentLoopTestDependencies(zero)
    await expect(zero.plugin(memoryCore, { topK: 0 })).rejects.toThrow(/topK must be an integer/)
  })

  it('rejects a segment ceiling above the render width', async () => {
    // A record bigger than maxLineChars is delivered as an excerpt, so the two
    // bounds are one decision, not two.
    const ctx = new Context()
    await mountAgentLoopTestDependencies(ctx)
    await expect(ctx.plugin(memoryCore, { segmentChars: 400, maxLineChars: 240 }))
      .rejects.toThrow(/must not exceed maxLineChars/)
  })

  it('rejects a score floor outside the unit interval', async () => {
    const ctx = new Context()
    await mountAgentLoopTestDependencies(ctx)
    await expect(ctx.plugin(memoryCore, { minScore: 1.5 })).rejects.toThrow(/minScore must be within/)
  })
})

describe('write gate', () => {
  it('filters small talk in the in-process store too, not only in thin-client mode', async () => {
    // 闸门曾在 thin-client 分支里，本地模式绕过了它：同一段寒暄经服务写入会被拦，
    // 直接进内存却会留下，召回随即被噪声稀释。这条用例把两种模式钉在同一行为上。
    const ctx = await mount({ writeGate: true })
    const session = ctx.sessions.create(SessionId('gate-local'))
    recordTurn(session, 1, '你好', '你好！有什么可以帮你？')
    recordTurn(session, 2, '记住：鉴权失败要检查 X-CSRF-Token 是否随请求带上。', '好的。')

    const greeting = await fire(ctx, sessionAgent(session), 3, 1, '你好')
    expect(greeting.injected).toBeUndefined()

    const remembered = await fire(ctx, sessionAgent(session), 4, 1, '鉴权失败怎么办？')
    expect(remembered.injected).toContain('X-CSRF-Token')
  })

  it('keeps a short turn that carries an explicit "remember this"', async () => {
    // 显式要求优先于长度：最短的那种轮次恰恰最该留下。
    const ctx = await mount({ writeGate: true })
    const session = ctx.sessions.create(SessionId('gate-explicit'))
    recordTurn(session, 1, '记住：端口是 8090。', '好。')

    const { injected } = await fire(ctx, sessionAgent(session), 2, 1, '端口是多少？')
    expect(injected).toContain('8090')
  })
})

describe('memory_write', () => {
  interface WriteValue {
    ok: boolean
    queued: boolean
    note: string
  }

  it('stores the statement and makes it recallable', async () => {
    // The turn gate never sees this: no "记住", no change, no repair. It is the
    // model saying the statement matters, which is the whole point of the tool.
    const ctx = await mount()
    const session = ctx.sessions.create(SessionId('tool-write'))
    recordTurn(session, 1, '随便聊聊天气。', '今天不错。')

    const write = ctx.tools.get('memory_write')
    expect(write).toBeDefined()
    const value = await write?.execute(
      { text: '备份只做 PostgreSQL，Qdrant 的向量可以从原文重算。' },
      { agent: sessionAgent(session), signal: SIGNAL } as unknown as ToolRunContext,
    ) as WriteValue
    expect(value.ok).toBe(true)
    expect(value.queued).toBe(false)

    const search = ctx.tools.get('memory_search')
    const found = await search?.execute(
      { query: 'Qdrant 向量重算' },
      { signal: SIGNAL } as ToolRunContext,
    ) as { hits: { text: string }[] }
    expect(found.hits.some(hit => hit.text.includes('Qdrant'))).toBe(true)
  })

  it('rejects an empty statement instead of storing whitespace', async () => {
    const ctx = await mount()
    const write = ctx.tools.get('memory_write')
    await expect(write?.execute(
      { text: '   ' },
      { signal: SIGNAL } as ToolRunContext,
    )).rejects.toThrow()
  })
})
