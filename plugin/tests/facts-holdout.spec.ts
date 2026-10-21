/**
 * Blind holdout for the fact layer.
 *
 * The structural set was written beside the rules, so passing it is a training
 * score. These ten scenarios were written afterwards, from phrasings the rules
 * were not designed against (English keys, bare values, indirect wordings), and
 * are run once with no rule changes. The test pins *which* cases miss, not just
 * how many: that is the honest number to quote, and it names the boundary the
 * next stage has to move.
 */

import { describe, expect, it } from 'vitest'
import { captureSegments } from '../src/capture.ts'
import { createFactStore, extractFacts } from '../src/facts.ts'
import { renderInjection } from '../src/render.ts'
import { createInMemoryStore } from '../src/store.ts'

interface Probe {
  readonly name: string
  readonly turns: readonly (readonly [prompt: string, reply: string])[]
  readonly query: string
  /** The value a reader would expect the system to know. */
  readonly want: string
}

const PROBES: readonly Probe[] = [
  {
    name: 'english key',
    turns: [['listen_port 5432', '已记录。']],
    query: '端口是多少',
    want: '5432',
  },
  {
    name: 'bare licence value',
    turns: [['这个仓库用 GPL-3.0', '已记录。']],
    query: '许可证是什么',
    want: 'GPL-3.0',
  },
  {
    name: 'well-known port with a warning',
    turns: [['端口占用 22，别关', '好的。']],
    query: '端口是多少',
    want: '22',
  },
  {
    name: 'major-only version',
    turns: [['API 版本 v2 已上线', '好的。']],
    query: '版本是多少',
    want: '2',
  },
  {
    name: 'path introduced by a preposition',
    turns: [['日志在 /data/logs/nginx 下', '好的。']],
    query: '日志路径在哪',
    want: '/data/logs/nginx',
  },
  {
    name: 'domain with a colon',
    turns: [['域名是 api.example.com', '好的。']],
    query: '服务地址是什么',
    want: 'api.example.com',
  },
  {
    name: 'owner after a full-width colon',
    turns: [['值班负责人：李四', '好的。']],
    query: '负责人是谁',
    want: '李四',
  },
  {
    name: 'value behind a verb phrase',
    turns: [['端口先按 8443 走', '好的。']],
    query: '端口是多少',
    want: '8443',
  },
  {
    name: 'subject stated, predicate word omitted',
    turns: [['前端改到 3000 了', '好的。']],
    query: '前端端口是多少',
    want: '3000',
  },
  {
    name: 'product version beside the word',
    turns: [['数据库版本 PostgreSQL 18', '好的。']],
    query: '版本是多少',
    want: '18',
  },
]

/** Run one probe and report whether the wanted value reached the model. */
function knows(probe: Probe): boolean {
  const store = createInMemoryStore({ maxRecords: 50, identifierWeight: 1.5 })
  const facts = createFactStore({ maxFacts: 50 })
  let sequence = 0
  probe.turns.forEach(([prompt, reply], index) => {
    const turn = index + 1
    for (const segment of captureSegments(prompt, [reply], 240)) {
      const extraction = extractFacts(segment.text)
      facts.apply('session', turn, turn * 1000, extraction)
      sequence += 1
      store.record({
        id: `m-${String(sequence)}`,
        sessionId: 'session',
        kind: 'turn',
        text: segment.text,
        createdAt: turn * 1000,
        turn,
        ...(extraction.asserted.length === 0 ? {} : { facts: extraction.asserted }),
      })
    }
  })
  const view = facts.query('session', probe.query)
  const text = renderInjection(view, [], { budgetChars: 1200, factBudgetChars: 400, maxLineChars: 240 })
  return (text ?? '').includes(probe.want)
}

describe('fact layer holdout', () => {
  it('knows 5 of 10 blind cases, and misses exactly these', () => {
    const missed = PROBES.filter(probe => !knows(probe)).map(probe => probe.name)
    // Known boundary: the rule extractor reads `predicate word + value`. A bare
    // value (`用 GPL-3.0`), a value introduced by a preposition, a major-only
    // version, a product version without dotted parts, and a number whose
    // predicate word was left implicit all fall outside it. Adding one more
    // pattern per case would raise this number and teach nothing: the next
    // stage is an extractor, not more regexes.
    expect(missed.sort()).toEqual([
      'bare licence value',
      'major-only version',
      'path introduced by a preposition',
      'product version beside the word',
      'subject stated, predicate word omitted',
    ])
    expect(PROBES.length - missed.length).toBe(5)
  })
})
