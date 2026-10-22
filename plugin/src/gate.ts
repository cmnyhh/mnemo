/**
 * Write gate: decide what a finished turn contributes to memory.
 *
 * Two different things can be produced from one turn, and they have opposite
 * economics:
 *
 * - **A conversation log entry** — who said what in this turn. Cheap (no model
 *   call), complete, and the thing that answers "what did we talk about". Its
 *   value comes from coverage, so the gate lets it through by default.
 * - **Extracted facts / preferences** — what is durably true. Expensive (one
 *   model call per turn) and worthless if it fires on small talk, so it stays
 *   behind the keyword signals.
 *
 * `mode: 'log'` (the default) captures every turn and reports the signal in
 * `trigger`; the service writes the log entry unconditionally and only spends a
 * model call when the trigger says something happened. `mode: 'signal'` is the
 * old behaviour — nothing is sent unless a signal fired — and exists for hosts
 * that want strict filtering.
 *
 * @module @deepseek-ai/dsh-memory-core/gate
 */

/** Why a turn carries a signal worth spending extraction on. `none` means "log only". */
export type Trigger = 'explicit' | 'change' | 'repair' | 'none'

/** `log` records every turn; `signal` records only turns with a trigger. */
export type CaptureMode = 'log' | 'signal'

export interface GateInput {
  /** What the person typed. */
  readonly prompt: string
  /** Assistant replies in the same exchange. */
  readonly replies: readonly string[]
}

export interface GateOptions {
  /** When false nothing is filtered (default true). */
  readonly enabled?: boolean
  /**
   * Below this many characters (prompt + replies) a turn is never remembered
   * (default 24). Acknowledgements and one-liners carry no state worth keeping.
   */
  readonly minChars?: number
  /** `log` (default) records every turn; `signal` only records triggered turns. */
  readonly mode?: CaptureMode
}

export interface GateDecision {
  readonly capture: boolean
  readonly trigger: Trigger
  /** Short human-readable reason; emitted at debug level, not shown to the model. */
  readonly reason: string
}

/** "记住 / 以后都" — the person is asking for this to persist. */
const EXPLICIT = /(记住|记一下|记下来?|以后都|今后都|以后不要|永远都|别再忘|别忘了)/u

/** The model reports something that changed — or the person asks for a change. */
const CHANGE = /(已(经)?(改|修改|改成|换成|部署|上线|升级|迁移|重装|重构|发布|删除|新增|添加|修复)|改成了|换成了|现在用|已切换到|改成|换成|改为|改到|调整成|设置成)/u

/** Something was broken. */
const PROBLEM = /(报错|错误|失败|打不开|连不上|超时|异常|崩了|挂了|不可用|500|502|503)/u

/** …and then it was not. */
const FIXED = /(根因|原因是|问题在于|解决的?办法|修好|修复|已恢复|就好了|原因是)/u

function join(input: GateInput): string {
  return [input.prompt, ...input.replies].join('\n').trim()
}

/** The signal, independent of whether the turn is captured. */
function signalOf(input: GateInput): Trigger {
  if (EXPLICIT.test(input.prompt)) return 'explicit'
  const replies = input.replies.join('\n')
  if (PROBLEM.test(input.prompt) && FIXED.test(replies)) return 'repair'
  if (CHANGE.test(replies) || CHANGE.test(input.prompt)) return 'change'
  return 'none'
}

export function decideCapture(input: GateInput, options: GateOptions = {}): GateDecision {
  if (options.enabled === false) {
    // 关掉闸门＝每轮都按「有信号」处理：记日志，也照常抽事实。
    // 这里不能报 explicit——那会让服务端跳过形状过滤，等于把所有注入块都收进来。
    return { capture: true, trigger: 'change', reason: '闸门已关闭，全部写入' }
  }
  const minChars = options.minChars ?? 24
  const mode: CaptureMode = options.mode ?? 'log'
  const text = join(input)
  if (text.length === 0) {
    return { capture: false, trigger: 'none', reason: '空轮，无可写入内容' }
  }

  const trigger = signalOf(input)

  // 显式优先于长度：一句「记住：端口是 8080」很短，但正是最该留的那种。
  if (trigger === 'explicit') {
    return { capture: true, trigger, reason: '用户显式要求记住' }
  }

  // 「端口改成 9090」只有九个字，但它是本轮最值得记的东西。
  // 所以长度只用来挡「没有任何信号」的短轮，不用来否决有信号的短轮。
  if (text.length < minChars && trigger === 'none') {
    return { capture: false, trigger, reason: `内容过短（${String(text.length)} 字）且无变更信号，不记` }
  }

  if (mode === 'signal' && trigger === 'none') {
    return { capture: false, trigger, reason: '既非显式、也非变更或修复，不记' }
  }
  if (trigger === 'none') {
    return { capture: true, trigger, reason: '记对话日志（不抽事实）' }
  }
  return { capture: true, trigger, reason: '本轮有信号，记日志并抽事实' }
}
