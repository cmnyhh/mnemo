/**
 * Project the pinned (T0) records into the managed block of `AGENTS.md`.
 *
 * DSH reads `AGENTS.md` natively through `agent-instructions` and has **no
 * write path** — so the file is written here, from the outside, using the path
 * convention the harness already has. That keeps T0 durable, visible, and
 * editable by the person it describes, which a runtime-only injection is not.
 *
 * The truth is still the service: `tier='T0'` decides what goes in, this file is
 * a projection. Each machine renders its own copy and converges upward.
 *
 * Three properties matter more than the feature itself:
 *
 * - **Only the managed block is touched.** A user may keep their own
 *   instructions in the same file; anything outside `memory:begin`/`memory:end`
 *   is byte-for-byte preserved.
 * - **Unchanged content is not written.** A daily check must not make an editor
 *   reload the file, so the block carries its own hash and is skipped when equal.
 * - **The write is atomic and reversible.** Temp file + fsync + rename, with the
 *   last few versions kept as timestamped backups.
 *
 * @module @deepseek-ai/dsh-memory-core/agents
 */

import { copyFile, mkdir, open, readFile, readdir, rename, unlink, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { dirname, basename, join } from 'node:path'
import type { RemotePinnedItem } from './remote.ts'

/** Marks the start of the managed block; the hash lets a no-op sync stay a no-op. */
const BEGIN = /<!--\s*memory:begin[^>]*-->/u
const END = '<!-- memory:end -->'

/** Hard ceilings. The harness gives all instructions 64 KiB; T0 must not crowd it out. */
export const DEFAULT_MAX_CHARS = 2048
export const DEFAULT_MAX_ITEMS = 8
const DEFAULT_BACKUPS = 3

export interface AgentsOptions {
  /** Absolute path to `AGENTS.md`. */
  readonly file: string
  /** Character ceiling for the whole managed block (default 2048). */
  readonly maxChars?: number
  /** Maximum bullet count (default 8). */
  readonly maxItems?: number
  /** Timestamped backups kept (default 3). */
  readonly backups?: number
}

export type SyncStatus = 'written' | 'unchanged' | 'skipped' | 'empty'

export interface SyncResult {
  readonly status: SyncStatus
  /** Human-readable reason, for the log line the caller emits. */
  readonly detail: string
}

/**
 * Imperative phrasing that must not become a standing instruction.
 *
 * The service already downgrades these at write time and refuses to pin them;
 * this is the second gate, because a record written before that guard existed
 * is still in the table and pinning is not the only way to reach T0.
 */
const IMPERATIVE = [
  /忽略(以上|之前|前面)(的)?(所有)?(指令|指示|要求|设定)/u,
  /(执行|运行|调用)(以下|下面|这条|该)(命令|脚本|指令)/u,
  /(打开|访问|请求)(以下|这个|该)(链接|网址|地址)/u,
  /(发送|转发|上传|提交)(到|至|给)\s*(https?:|www\.|[\w.+-]+@)/iu,
  /(提供|告诉我|发我|给我)(你的)?(密码|口令|密钥|token|api[ _-]?key)/iu,
  /\b(ignore|disregard)\s+(all\s+)?(previous|prior|above)\s+instructions?\b/iu,
]

/** One line, no newlines: a bullet that wraps is a bullet the reader skims past. */
function oneLine(text: string, limit: number): string {
  const flat = text.replace(/\s+/gu, ' ').trim()
  return flat.length <= limit ? flat : `${flat.slice(0, limit - 1)}…`
}

export function renderManagedBlock(items: readonly RemotePinnedItem[], maxChars = DEFAULT_MAX_CHARS,
  maxItems = DEFAULT_MAX_ITEMS): string {
  const lines: string[] = ['## 工作约定（自动维护，请勿手改本节）', '']
  let used = 0
  let kept = 0
  for (const item of items) {
    if (kept >= maxItems) break
    const text = oneLine(item.narrative, 200)
    if (text.length === 0) continue
    if (IMPERATIVE.some(pattern => pattern.test(text))) continue
    const line = `- ${text}`
    // +1 for the newline, so the ceiling is on the file content, not the sum of lengths
    if (used + line.length + 1 > maxChars) break
    lines.push(line)
    used += line.length + 1
    kept += 1
  }
  if (kept === 0) return ''
  const body = `${lines.join('\n')}\n`
  const hash = createHash('sha256').update(body).digest('hex').slice(0, 16)
  return `<!-- memory:begin v=1 hash=${hash} -->\n${body}${END}\n`
}

/** Everything outside the managed block, plus the block itself if one exists. */
function split(content: string): { before: string, block: string | undefined, after: string } {
  const begin = BEGIN.exec(content)
  if (begin === null) return { before: content, block: undefined, after: '' }
  const start = begin.index
  const endAt = content.indexOf(END, start)
  if (endAt < 0) return { before: content, block: undefined, after: '' }
  const end = endAt + END.length
  return { before: content.slice(0, start), block: content.slice(start, end), after: content.slice(end) }
}

async function atomicWrite(file: string, content: string): Promise<void> {
  const tmp = `${file}.tmp-${String(process.pid)}`
  const handle = await open(tmp, 'w')
  try {
    await handle.writeFile(content, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  try {
    await rename(tmp, file)
  } catch {
    // Windows 不允许 rename 覆盖；退化成覆盖写，原子性略降但不会留下半个文件
    await writeFile(file, content, 'utf8')
    await unlink(tmp).catch(() => undefined)
  }
}

async function backup(file: string, keep: number): Promise<void> {
  const dir = dirname(file)
  const prefix = `${basename(file)}.bak.`
  // 时间戳 + 毫秒：连续几次改写可能落在同一秒，只按秒命名会互相覆盖，
  // 「保留最近三份」就退化成「只留一份」。后缀定长，字典序仍然等于时间序。
  const stamp = `${new Date().toISOString().replace(/[-:]/gu, '').slice(0, 15)}-${String(Date.now())}`
  await copyFile(file, join(dir, `${prefix}${stamp}`)).catch(() => undefined)
  const entries = await readdir(dir).catch(() => [] as string[])
  const stale = entries.filter(name => name.startsWith(prefix)).sort().reverse().slice(keep)
  await Promise.all(stale.map(name => unlink(join(dir, name)).catch(() => undefined)))
}

/**
 * Render `items` into the managed block of {@link AgentsOptions.file}.
 *
 * @returns `written` when the file changed, `unchanged` when the hash matched,
 *   `empty` when nothing survived the ceilings (the block is then removed), or
 *   `skipped` when the write failed — a sync failure must never break a turn.
 */
export async function syncAgentsFile(options: AgentsOptions,
  items: readonly RemotePinnedItem[]): Promise<SyncResult> {
  const maxChars = options.maxChars ?? DEFAULT_MAX_CHARS
  const maxItems = options.maxItems ?? DEFAULT_MAX_ITEMS
  const keep = options.backups ?? DEFAULT_BACKUPS
  const block = renderManagedBlock(items, maxChars, maxItems)

  try {
    const existing = await readFile(options.file, 'utf8').catch(() => undefined)
    const current = existing === undefined ? { before: '', block: undefined, after: '' } : split(existing)

    if (block === '') {
      if (current.block === undefined) return { status: 'empty', detail: '没有可写入的常驻条目，文件保持原样' }
      const next = `${current.before}${current.after}`.replace(/\n{3,}/gu, '\n\n')
      await atomicWrite(options.file, next)
      return { status: 'written', detail: '常驻集合为空，已移除受管区块' }
    }

    if (current.block !== undefined && normalize(current.block) === normalize(block)) {
      return { status: 'unchanged', detail: '区块内容未变' }
    }

    if (existing === undefined) {
      await mkdir(dirname(options.file), { recursive: true })
      await atomicWrite(options.file, block)
      return { status: 'written', detail: '文件不存在，已新建并写入受管区块' }
    }

    await backup(options.file, keep)
    const next = current.block === undefined
      // 追加：保证与前文之间恰好一个空行，不要让用户的行尾空白成为区块的一部分
      ? `${existing.replace(/\s*$/u, '')}\n\n${block}`
      : `${current.before}${block}${current.after}`
    await atomicWrite(options.file, next)
    return { status: 'written', detail: `已更新受管区块（${String(items.length)} 条候选）` }
  } catch (error) {
    return { status: 'skipped', detail: `写入失败: ${error instanceof Error ? error.message : String(error)}` }
  }
}

function normalize(text: string): string {
  return text.replace(/\r\n/gu, '\n').trim()
}
