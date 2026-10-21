/**
 * Write gate: decide whether a finished turn is worth remembering.
 *
 * Extracting every turn costs more model calls than the conversation itself and
 * mostly produces noise — small talk, acknowledgements, restatements. The records
 * that matter cluster around two events: something changed, and something broke
 * and was fixed. Everything else can go unremembered without anyone noticing.
 *
 * The gate is deliberately **client-side**: the plugin is the only component that
 * sees the turn, and a service-side gate would have to be paid for on every path
 * that writes. Its judgement is textual on purpose — the plugin's contract is the
 * conversation, not the tool log, and a keyword heuristic that errs toward
 * "don't write" is the right failure direction: recall quality does not depend on
 * capturing every turn, but it does degrade under a flood of trivial ones.
 *
 * @module @deepseek-ai/dsh-memory-core/gate
 */

/** Why a turn passed the gate. `none` means it did not. */
export type Trigger = 'explicit' | 'change' | 'repair' | 'none'

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

export function decideCapture(input: GateInput, options: GateOptions = {}): GateDecision {
  if (options.enabled === false) {
    return { capture: true, trigger: 'none', reason: '闸门已关闭，全部写入' }
  }
  const minChars = options.minChars ?? 24

  // 显式优先于长度：一句「记住：端口是 8080」很短，但正是最该留的那种。
  if (EXPLICIT.test(input.prompt)) {
    return { capture: true, trigger: 'explicit', reason: '用户显式要求记住' }
  }

  const text = join(input)
  const replies = input.replies.join('\n')
  // 「端口改成 9090」只有九个字，但它是本轮最值得记的东西。
  // 所以长度只用来过滤「没有任何信号」的短轮，不用来否决有信号的短轮。
  if (text.length < minChars && !CHANGE.test(text)) {
    return { capture: false, trigger: 'none', reason: `内容过短（${String(text.length)} 字）且无变更信号，不抽取` }
  }

  if (PROBLEM.test(input.prompt) && FIXED.test(replies)) {
    return { capture: true, trigger: 'repair', reason: '先失败后解决，值得记成 episode' }
  }
  if (CHANGE.test(replies) || CHANGE.test(input.prompt)) {
    return { capture: true, trigger: 'change', reason: '本轮发生了变更' }
  }
  return { capture: false, trigger: 'none', reason: '既非显式、也非变更或修复，不抽取' }
}
