import { describe, expect, it } from 'vitest'
import { captureSegments } from '../src/capture.ts'

const PROMPT = '生产环境端口是多少？'
const FILLER = '这一段讲的是迁移的历史背景与当时的取舍，和端口没有关系。'

describe('capture segmentation', () => {
  it('keeps a short exchange as one record', () => {
    const segments = captureSegments(PROMPT, ['生产环境端口固定为 8080。'], 240)
    expect(segments).toHaveLength(1)
    expect(segments[0]?.text).toContain('生产环境端口固定为 8080')
    // Short enough to stay whole, so no context prefix is needed.
    expect(segments[0]?.context).toBeUndefined()
  })

  it('splits a long exchange at paragraph boundaries', () => {
    const reply = [FILLER, '生产环境端口固定为 8080，改动要走变更流程。', '最后再确认一次上面的结论。'].join('\n\n')
    const segments = captureSegments(PROMPT, [reply], 60)

    expect(segments.length).toBeGreaterThan(1)
    for (const segment of segments) expect(segment.text.length).toBeLessThanOrEqual(60)
    // The answer is a record of its own rather than a window into a blob.
    expect(segments.some(segment => segment.text.startsWith('助手: 生产环境端口固定为 8080'))).toBe(true)
  })

  it('carries the question beside every detached answer paragraph', () => {
    const reply = [FILLER, '生产环境端口固定为 8080。', '最后再确认一次上面的结论。'].join('\n\n')
    const segments = captureSegments(PROMPT, [reply], 60)
    const answers = segments.filter(segment => segment.text.startsWith('助手: '))

    expect(answers.length).toBeGreaterThan(0)
    for (const segment of answers) expect(segment.context).toBe(PROMPT)
    // The prompt record itself does not need to repeat the prompt.
    const asks = segments.filter(segment => segment.text.startsWith('用户: '))
    expect(asks.every(segment => segment.context === undefined)).toBe(true)
  })

  it('records the concluding reply, not mid-turn narration', () => {
    // A step that calls a tool first says "先看一下" — that text is not a fact and
    // would add noise to every later query sharing a word with it.
    const segments = captureSegments(PROMPT, ['我先查一下配置文件。', '生产环境端口固定为 8080。'], 240)
    expect(segments).toHaveLength(1)
    expect(segments[0]?.text).not.toContain('我先查')
    expect(segments[0]?.text).toContain('8080')
  })

  it('hard-splits a line with no sentence boundary and loses nothing', () => {
    const wall = '端'.repeat(700)
    const segments = captureSegments('端口', [wall], 120)

    for (const segment of segments) expect(segment.text.length).toBeLessThanOrEqual(120)
    const recovered = segments
      .filter(segment => segment.text.startsWith('助手: '))
      .map(segment => segment.text.replace(/^助手: /u, ''))
      .join('')
    expect(recovered).toHaveLength(700)
  })

  it('drops an exchange with an empty answer down to its prompt', () => {
    const segments = captureSegments(PROMPT, [], 240)
    expect(segments).toHaveLength(1)
    expect(segments[0]?.text).toBe(`用户: ${PROMPT}`)
  })
})
