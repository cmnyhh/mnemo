/**
 * Volatile config fields arrive as holders, plain ones as values. Both shapes
 * have to read the same way, because the plugin's behaviour must not depend on
 * whether the deployment happens to expose a settings panel.
 */
import { describe, expect, it } from 'vitest'
import { liveString } from '../src/live.ts'

function holder(initial: string | undefined): { get(): string | undefined; set(next: string | undefined): void } {
  let current = initial
  return { get: () => current, set: (next) => { current = next } }
}

describe('liveString', () => {
  it('reads a plain string, trimmed', () => {
    expect(liveString('  https://memory.example  ')()).toBe('https://memory.example')
  })

  it('reads a volatile holder', () => {
    expect(liveString(holder('https://memory.example'))()).toBe('https://memory.example')
  })

  it('follows the holder when the settings panel rewrites it', () => {
    // The whole point of volatile: no remount, so the reader has to see the new value.
    const field = holder('old-token')
    const read = liveString(field)
    expect(read()).toBe('old-token')
    field.set('new-token')
    expect(read()).toBe('new-token')
  })

  it('reports an unset field as empty rather than as a missing reader', () => {
    expect(liveString(undefined)()).toBe('')
    expect(liveString(holder(undefined))()).toBe('')
    expect(liveString(null)()).toBe('')
    expect(liveString(42)()).toBe('')
  })
})
