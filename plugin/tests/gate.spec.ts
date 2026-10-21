import { describe, expect, it } from 'vitest'
import { decideCapture } from '../src/gate.ts'

describe('decideCapture', () => {
  it('显式要求记住的短句也要写入', () => {
    const decision = decideCapture({ prompt: '记住：端口是 8080', replies: ['好的。'] })
    expect(decision.capture).toBe(true)
    expect(decision.trigger).toBe('explicit')
  })

  it('闲聊不写入', () => {
    const decision = decideCapture({
      prompt: '今天天气不错，我们随便聊聊吧，你觉得怎么样呢',
      replies: ['是挺好的，适合出去走走。你最近有什么打算吗？'],
    })
    expect(decision.capture).toBe(false)
    expect(decision.trigger).toBe('none')
  })

  it('过短的轮次不写入', () => {
    const decision = decideCapture({ prompt: '好的', replies: ['嗯。'] })
    expect(decision.capture).toBe(false)
  })

  it('发生了变更就写入', () => {
    const decision = decideCapture({
      prompt: '把知识库同步改成分批上传，一次 50 个',
      replies: ['已改成每批 50 个，并且失败会重试三次。'],
    })
    expect(decision.capture).toBe(true)
    expect(decision.trigger).toBe('change')
  })

  it('先失败后解决写入成 episode', () => {
    const decision = decideCapture({
      prompt: '同步一直报错 502，怎么处理？',
      replies: ['根因是上游反代超时太短，调大到 120 秒后已恢复。'],
    })
    expect(decision.capture).toBe(true)
    expect(decision.trigger).toBe('repair')
  })

  it('只报错没解决时不写入', () => {
    const decision = decideCapture({
      prompt: '这个接口一直超时，你帮我看看是怎么回事',
      replies: ['我先确认一下网络，稍等。'],
    })
    expect(decision.capture).toBe(false)
  })

  it('关掉闸门就全部写入', () => {
    const decision = decideCapture(
      { prompt: '嗯', replies: ['嗯。'] },
      { enabled: false },
    )
    expect(decision.capture).toBe(true)
  })

  it('minChars 只过滤没有信号的短轮', () => {
    const change = { prompt: '把超时改成 60 秒', replies: ['已改成 60 秒。'] }
    expect(decideCapture(change, { minChars: 8 }).capture).toBe(true)
    // 短变更正是最值得记的东西，长度阈值不能否决它
    expect(decideCapture(change, { minChars: 200 }).capture).toBe(true)

    const chatter = { prompt: '那好吧', replies: ['嗯。'] }
    expect(decideCapture(chatter, { minChars: 8 }).capture).toBe(false)
    // 调低阈值也不会把闲聊变成记忆：没有信号就是不写
    expect(decideCapture(chatter, { minChars: 1 }).capture).toBe(false)
  })
})
