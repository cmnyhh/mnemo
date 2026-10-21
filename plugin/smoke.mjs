// 假宿主冒烟：证明 bundle 能被 cordis 式挂载、写入、下一轮召回、工具可调用。
//
// 用法：node smoke.mjs [index.js 的路径]，缺省为同目录的 ./index.js。
//
// 插件把 @deepseek-ai/dsh-llm、dsh-tools 留给宿主提供，所以这里在临时目录里
// 复制一份 bundle、补上最小桩模块再加载，不动你机器上的任何目录。
import { mkdtemp, mkdir, writeFile, copyFile, rm, readdir, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const STUBS = {
  'dsh-llm': 'let n = 0\nexport function createUserMessage(input) { return { id: `msg-${++n}`, role: \'user\', ...input } }\n',
  'dsh-tools': 'export function defineTool(definition) { return definition }\n',
}

/** 缺省目标：同目录的 index.js，否则本目录下最新的 dsh-memory-core-<版本>/index.js。 */
async function defaultSource() {
  const here = dirname(fileURLToPath(import.meta.url))
  const same = join(here, 'index.js')
  try {
    await stat(same)
    return same
  } catch {}
  const candidates = (await readdir(here)).filter(name => name.startsWith('dsh-memory-core-')).sort()
  const latest = candidates.at(-1)
  if (latest === undefined) throw new Error('找不到 index.js：把解压后的 index.js 路径作为参数传入')
  return join(here, latest, 'index.js')
}

const source = resolve(process.argv[2] ?? await defaultSource())
const sandbox = await mkdtemp(join(tmpdir(), 'memcore-smoke-'))

async function stage() {
  await copyFile(source, join(sandbox, 'index.js'))
  for (const [name, body] of Object.entries(STUBS)) {
    const dir = join(sandbox, 'node_modules', '@deepseek-ai', name)
    await mkdir(dir, { recursive: true })
    await writeFile(join(dir, 'package.json'), JSON.stringify({
      name: `@deepseek-ai/${name}`, version: '0.0.0', type: 'module', main: 'index.js',
    }))
    await writeFile(join(dir, 'index.js'), body)
  }
  return pathToFileURL(join(sandbox, 'index.js')).href
}

const checks = []
const check = (name, ok, detail = '') => checks.push({ name, ok, detail })

console.log(`目标: ${source}`)
const entry = await stage()
const plugin = await import(entry)

const handlers = new Map()
const tools = []
const logs = []
const ctx = {
  logger: { warn: (m) => logs.push(['warn', m]), info: () => {}, debug: () => {} },
  on(event, fn) { handlers.set(event, [...(handlers.get(event) ?? []), fn]) },
  tools: { register(tool) { tools.push(tool) } },
}

plugin.apply(ctx, plugin.Config({}))

check('导出 name', plugin.name === 'memory-core')
check('注册两个工具', tools.length === 2
  && tools.some(t => t.name === 'memory_search')
  && tools.some(t => t.name === 'memory_write'), tools.map(t => t.name).join(','))
check('挂载 session/event', handlers.has('session/event'))
check('挂载 agent/pre-step', handlers.has('agent/pre-step'))

const text = (t) => [{ type: 'text', text: t }]
const emit = (event, session, payload) => {
  for (const fn of handlers.get(event) ?? []) fn(session, payload)
}
const preStep = async (prompt) => {
  let injected
  const next = async () => ({ kind: 'continue', messages: [] })
  for (const fn of handlers.get('agent/pre-step') ?? []) {
    const decision = await fn({
      agent: { session: { id: 'sess-1' } },
      messages: [{ source: { kind: 'user' }, content: text(prompt) }],
      step: 1,
      signal: { aborted: false },
    }, next)
    injected = decision.messages
  }
  return injected.map(m => m.content.map(b => b.text).join('')).join('\n')
}

// 一轮完整对话
const session = { id: 'sess-1' }
const time = Date.now()
emit('session/event', session, { type: 'turn/start', data: { turn: 1 }, time })
emit('session/event', session, {
  type: 'user/message',
  data: { source: { kind: 'user' }, content: text('记住：mnemo 的部署端口是 8090，部署目录 /srv/memory-service。') },
  time,
})
emit('session/event', session, {
  type: 'assistant/message',
  data: { message: { content: text('好的，已记录 mnemo 的部署端口是 8090，部署目录 /srv/memory-service。') } },
  time,
})
emit('session/event', session, { type: 'turn/end', data: {}, time })

const block = await preStep('mnemo 的部署端口是多少？')
check('下一轮注入非空', block.length > 0, `${block.length} 字符`)
check('注入命中端口', block.includes('8090'), block.slice(0, 120).replace(/\n/g, ' ⏎ '))
// `<memory_context>` 信封由服务端渲染，本地 in-process 模式没有；
// 这里只断言本地渲染头，信封由服务端不变量巡检覆盖。
check('注入带本地渲染头', block.includes('Recalled long-term memory'), '')

// 工具调用
const tool = tools[0]
const value = await tool.execute({ query: '部署端口' }, { agent: { session: { id: 'sess-1' } } })
check('工具召回命中', Array.isArray(value.hits) && value.hits.some(h => h.text.includes('8090')), JSON.stringify(value.hits.map(h => h.id)))
const rendered = tool.output.render({}, value)
check('工具可渲染', Array.isArray(rendered) && rendered[0].text.length > 0)

// 远端模式：服务不可达时必须说"请求失败"，不能冒充"没有记忆"
const remoteTools = []
const remoteLogs = []
const remoteCtx = {
  logger: { warn: (m) => remoteLogs.push(String(m)), info: () => {}, debug: () => {} },
  on() {},
  tools: { register(tool) { remoteTools.push(tool) } },
}
plugin.apply(remoteCtx, plugin.Config({
  serviceUrl: 'http://127.0.0.1:1',
  serviceToken: 'dummy',
  projectId: 'smoke',
  serviceTimeoutMs: 800,
}))
const remoteTool = remoteTools.find(t => t.name === 'memory_search')
const missed = await remoteTool.execute({ query: '任何问题' }, { agent: { session: { id: 'sess-2' } } })
check('远端失败不冒充零命中',
  missed.hits.length === 0 && typeof missed.note === 'string' && missed.note.includes('没响应'),
  missed.note ?? '(无 note)')
const missedText = remoteTool.output.render({}, missed)[0].text
check('失败时不渲染 No memory matched',
  !missedText.includes('No memory matched'), missedText.slice(0, 80).replace(/\n/g, ' '))

// 写入闸门：寒暄不写
emit('session/event', session, { type: 'turn/start', data: { turn: 2 }, time: time + 1 })
emit('session/event', session, { type: 'user/message', data: { source: { kind: 'user' }, content: text('你好') }, time: time + 1 })
emit('session/event', session, { type: 'assistant/message', data: { message: { content: text('你好！') } }, time: time + 1 })
emit('session/event', session, { type: 'turn/end', data: {}, time: time + 1 })
const afterGreeting = await preStep('你好')
check('寒暄未被写入', !afterGreeting.includes('你好！'), afterGreeting.length + ' 字符')

let failed = 0
for (const c of checks) {
  if (!c.ok) failed++
  console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.detail ? '  — ' + c.detail : ''}`)
}
console.log(failed === 0 ? '\n冒烟通过' : `\n${failed} 项失败`)
if (logs.length > 0) console.log('日志:', JSON.stringify(logs))

await rm(sandbox, { recursive: true, force: true })
process.exit(failed === 0 ? 0 : 1)
