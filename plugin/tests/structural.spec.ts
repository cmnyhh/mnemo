/**
 * Structural evaluation of the fact layer.
 *
 * The calibration and holdout sets measure one-hop lexical recall; they cannot
 * see a contradiction, because both statements about a property are equally
 * similar to the question. This set is the one the fact layer has to pass:
 * attribute lookup, override, historical lookup, and — the half that matters
 * more — abstaining and not inventing.
 *
 * Scenarios run the real pipeline (capture → extract → store → render). The
 * assertions are on the rendered text, not on an intermediate object, so a
 * change that keeps the data right but stops saying it still fails.
 */

import { describe, expect, it } from 'vitest'
import { captureSegments } from '../src/capture.ts'
import { createFactStore, dropSupersededHits, extractFacts } from '../src/facts.ts'
import type { FactView } from '../src/facts.ts'
import { renderInjection } from '../src/render.ts'
import { createInMemoryStore } from '../src/store.ts'
import type { MemoryHit } from '../src/types.ts'

interface Scenario {
  readonly name: string
  /** One entry per turn: the human message and the final reply. */
  readonly turns: readonly (readonly [prompt: string, reply: string])[]
  readonly query: string
  /** No active value is expected for the query's predicate. */
  readonly silent?: boolean
  /** The query names no predicate at all. */
  readonly noPredicate?: boolean
  /** Substrings the injection must contain. */
  readonly expect?: readonly string[]
  /** Substrings the injection must not contain. */
  readonly reject?: readonly string[]
}

interface Outcome {
  readonly text: string | undefined
  readonly view: FactView
  readonly hits: readonly MemoryHit[]
}

/**
 * Run one scenario through capture, extraction, storage, filtering, rendering.
 * @param scenario - turns and the query to ask afterwards.
 * @returns rendered block plus the resolved fact view.
 */
function run(scenario: Scenario): Outcome {
  const store = createInMemoryStore({ maxRecords: 500, identifierWeight: 1.5 })
  const facts = createFactStore({ maxFacts: 200 })
  let sequence = 0
  scenario.turns.forEach(([prompt, reply], index) => {
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
  const view = facts.query('session', scenario.query)
  const hits = dropSupersededHits(
    store.recall({ text: scenario.query, limit: 6, minScore: 0.4 }),
    view,
  )
  return {
    text: renderInjection(view, hits, { budgetChars: 1200, factBudgetChars: 400, maxLineChars: 240 }),
    view,
    hits,
  }
}

/** Occurrences of one substring, for "exactly once" assertions. */
function count(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}

const CASES: readonly Scenario[] = [
  {
    name: 'answers a stated port',
    turns: [['部署端口固定为 8080', '好的。']],
    query: '端口是多少',
    expect: ['Current facts', '端口 = 8080'],
  },
  {
    name: 'keeps the qualifier when the statement has one',
    turns: [['生产环境端口是 9090', '好的。']],
    query: '生产环境端口是多少',
    expect: ['端口 = 9090'],
  },
  {
    name: 'keeps two subjects stated in the same turn apart',
    turns: [['前端端口 5173，后端端口 8080', '好的。']],
    query: '前端端口是多少',
    expect: ['端口 = 5173'],
    // The record that states both values is kept, because it is the record that
    // answers this question; the assertion is about the fact block, not the
    // quoted text.
    reject: ['前端端口 = 8080'],
  },
  {
    name: 'a qualifier marks an existing value instead of duplicating it',
    // The session already holds 9090 as its default. Naming it `后端` is a
    // rename, not a new value: a second row would leave two current ports.
    turns: [
      ['端口固定为 9090', '好的。'],
      ['前端端口 5173，后端端口 9090', '好的。'],
    ],
    query: '端口是多少',
    expect: ['后端端口 = 9090', '前端端口 = 5173'],
  },
  {
    name: 'keeps a record that answers its subject even when it states another',
    // The record states both ports. Asking about one of them must not discard
    // it: the other value is not a contradiction, and this is the only record
    // that carries the answer.
    turns: [['前端端口 5173，后端端口 9090', '好的。']],
    query: '前端端口是多少',
    expect: ['端口 = 5173', '前端端口 5173，后端端口 9090'],
  },
  {
    name: 'prints the qualifier only when several subjects answer at once',
    turns: [['前端端口 5173，后端端口 8080', '好的。']],
    query: '端口是多少',
    expect: ['前端端口 = 5173', '后端端口 = 8080'],
  },
  {
    name: 'answers a version',
    turns: [['版本升到 v2.3.1', '好的。']],
    query: '现在是什么版本',
    expect: ['版本 = 2.3.1'],
  },
  {
    name: 'answers a host',
    turns: [['服务地址是 192.0.2.10', '好的。']],
    query: '服务地址是什么',
    expect: ['地址 = 192.0.2.10'],
  },
  {
    name: 'answers a path',
    turns: [['日志路径是 /var/log/app', '好的。']],
    query: '日志路径在哪',
    expect: ['路径 = /var/log/app'],
  },
  {
    name: 'answers an owner',
    turns: [['负责人是 张三', '好的。']],
    query: '负责人是谁',
    expect: ['负责人 = 张三'],
  },
  {
    name: 'answers a licence',
    turns: [['许可证是 Apache-2.0', '好的。']],
    query: '许可证是什么',
    expect: ['许可证 = Apache-2.0'],
  },
  {
    name: 'a correction replaces the value instead of joining it',
    turns: [
      ['部署端口固定为 8080', '好的。'],
      ['端口改成 9090 了，8080 不用了', '已更新。'],
    ],
    query: '生产环境端口是多少',
    expect: ['端口 = 9090'],
    reject: ['8080'],
  },
  {
    name: 'three values leave only the last one current',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['端口改成 9090', '好的。'],
      ['端口最后定 7070', '好的。'],
    ],
    query: '端口是多少',
    expect: ['端口 = 7070'],
    reject: ['8080', '9090'],
  },
  {
    name: 'restating the same value does not create a second fact',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['再确认一次，端口是 8080', '确认，端口 8080。'],
    ],
    query: '端口是多少',
    expect: ['端口 = 8080'],
  },
  {
    name: 'a superseded record is not injected beside the current fact',
    // The first record repeats the query's own words, so it is recalled on
    // score alone; only the fact layer can tell that its value is out of date.
    turns: [
      ['生产环境端口是多少？生产环境端口固定为 8080，改动要走变更流程。', '好的。'],
      ['端口改成 9090 了，8080 不用了', '已更新。'],
    ],
    query: '生产环境端口是多少？',
    expect: ['端口 = 9090'],
    reject: ['端口 = 8080', '变更流程'],
  },
  {
    name: 'a historical query still sees the old value, marked',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['端口改成 9090', '好的。'],
    ],
    query: '之前端口是多少',
    expect: ['Superseded values', '端口 = 8080'],
  },
  {
    name: 'retiring the only value leaves nothing current',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['8080 不用了', '好。'],
    ],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a negated value is retired, not asserted',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['端口不要用 8080', '好。'],
    ],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a question states no value',
    turns: [['端口是 8080 还是 9090？', '两个都试过。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'version upgrade keeps the target',
    turns: [['版本从 v1.0 升到 v1.2', '好的。']],
    query: '版本是多少',
    expect: ['版本 = 1.2'],
  },
  {
    name: 'a later subjectless statement retires a qualified one',
    turns: [
      ['前端端口 5173', '好的。'],
      ['端口改成 9090', '好的。'],
    ],
    query: '端口是多少',
    expect: ['端口 = 9090'],
    reject: ['5173'],
  },
  {
    name: 'abstains on a predicate it has no value for',
    turns: [['pgvector 只作轻量模式', '好的。']],
    query: 'pgvector 支持多少维',
    silent: true,
  },
  {
    name: 'abstains when the licence was never stated',
    turns: [['记忆库用 PostgreSQL 18', '好的。']],
    query: '许可证是什么',
    silent: true,
  },
  {
    name: 'abstains when only another property was stated',
    turns: [['版本是 1.2', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'abstains when the owner is undecided',
    turns: [['负责人还没定', '明白。']],
    query: '负责人是谁',
    silent: true,
  },
  {
    name: 'abstains when the port is undecided',
    turns: [['端口还没定', '明白。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'does not react to a query that names no property',
    turns: [['记忆库决定用 PostgreSQL 18', '好的。']],
    query: '数据库选型是什么',
    noPredicate: true,
  },
  {
    name: 'leaves subject overlap to the lexical layer',
    turns: [['报错文案不许出现堆栈信息', '好的。']],
    query: '报错能不能带堆栈',
    noPredicate: true,
  },
  {
    name: 'a count next to the word port is not a port',
    turns: [['端口记录 12 条', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a count noun between the word and a four digit number is still a count',
    turns: [['端口配置记录 8080 用于对照旧环境', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a measure word after the number makes it a count',
    turns: [['这周端口变更 9090 次', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'port inside another word is not a port',
    turns: [['这个库支持 port 转发', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a statement about imports does not name a port',
    turns: [['import 端口模块', '好的。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'an instruction not to hardcode a port states no value',
    turns: [['这个端口号不要写死', '明白。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'version control is not a version number',
    turns: [['版本管理用 git', '好的。']],
    query: '版本是多少',
    silent: true,
  },
  {
    name: 'path planning is not a path',
    turns: [['路径规划算法要换', '好的。']],
    query: '路径在哪',
    silent: true,
  },
  {
    name: 'a licence to audit is not a licence',
    turns: [['开源协议要审计', '好的。']],
    query: '许可证是什么',
    silent: true,
  },
  {
    name: 'a question with two candidate values states neither',
    turns: [['端口是 8080 还是 9090？', '两个都试过。']],
    query: '端口是多少',
    silent: true,
  },
  {
    name: 'a question about retiring a value does not retire it',
    turns: [
      ['端口固定为 8080', '好的。'],
      ['8080 不用了吗？', '还在用。'],
    ],
    query: '端口是多少',
    expect: ['端口 = 8080'],
  },
  {
    name: 'a URL is not a port statement',
    turns: [['访问 http://localhost:8080 即可', '好的。']],
    query: '端口是多少',
    silent: true,
  },
]

describe('structural evaluation', () => {
  for (const scenario of CASES) {
    it(scenario.name, () => {
      const outcome = run(scenario)
      if (scenario.noPredicate) expect(outcome.view.predicate).toBeUndefined()
      if (scenario.silent) expect(outcome.view.active).toEqual([])
      for (const needle of scenario.expect ?? []) expect(outcome.text ?? '').toContain(needle)
      for (const needle of scenario.reject ?? []) expect(outcome.text ?? '').not.toContain(needle)
    })
  }

  it('states facts before recalled records', () => {
    const outcome = run({
      name: 'facts first',
      turns: [['部署端口固定为 8080', '端口 8080，改动要走变更流程。']],
      query: '端口是多少',
    })
    const text = outcome.text ?? ''
    expect(text.indexOf('Current facts')).toBeGreaterThanOrEqual(0)
    expect(text.indexOf('Recalled long-term memory')).toBeGreaterThan(text.indexOf('Current facts'))
  })

  it('spends the budget on the fact before the record', () => {
    const store = createInMemoryStore({ maxRecords: 10, identifierWeight: 1.5 })
    const facts = createFactStore({ maxFacts: 10 })
    const segment = '端口固定为 8080，' + '变更流程要走审批，'.repeat(20)
    const extraction = extractFacts(segment)
    facts.apply('s', 1, 1000, extraction)
    store.record({
      id: 'm-1', sessionId: 's', kind: 'turn', text: segment, createdAt: 1000, turn: 1,
      facts: extraction.asserted,
    })
    const view = facts.query('s', '端口是多少')
    const hits = dropSupersededHits(store.recall({ text: '端口是多少', limit: 6, minScore: 0.4 }), view)
    const text = renderInjection(view, hits, { budgetChars: 120, factBudgetChars: 120, maxLineChars: 240 })
    expect(text).toContain('端口 = 8080')
    expect(text).not.toContain('Recalled long-term memory')
  })

  it('does not print a fact heading when there is nothing current', () => {
    const outcome = run({
      name: 'no current fact',
      turns: [['配色方案偏冷，用蓝灰。', '好的。']],
      query: 'Kubernetes 的 ingress 怎么配？',
    })
    expect(outcome.text ?? '').not.toContain('Current facts')
  })

  it('prints a heading only once for a repeated value', () => {
    const outcome = run({
      name: 'repeated value',
      turns: [
        ['部署端口固定为 8080', '好的，端口 8080。'],
        ['确认端口 8080', '确认。'],
      ],
      query: '端口是多少',
    })
    expect(count(outcome.text ?? '', 'Current facts')).toBe(1)
    expect(count(outcome.text ?? '', '端口 = 8080')).toBe(1)
  })
})
