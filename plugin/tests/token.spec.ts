/**
 * Token indirection: a bundle's patch file is distributed with the package, so
 * the token itself cannot live there. These cases pin the three accepted forms
 * and, more importantly, the two ways to fail loudly.
 */
import { describe, expect, it } from 'vitest'
import { resolveToken } from '../src/token.ts'
import type { TokenSources } from '../src/token.ts'

function sources(overrides: Partial<TokenSources> = {}): TokenSources {
  return {
    env: {},
    readFile: () => {
      throw new Error('ENOENT')
    },
    ...overrides,
  }
}

describe('resolveToken', () => {
  it('passes a literal token through, trimmed', () => {
    // Hand-written patches and the current README both use a literal.
    expect(resolveToken('  abc123  ', sources())).toBe('abc123')
  })

  it('treats an unset value as empty so the caller can fail at load', () => {
    expect(resolveToken(undefined, sources())).toBe('')
    expect(resolveToken('   ', sources())).toBe('')
  })

  it('reads `env:NAME` from the environment', () => {
    expect(resolveToken('env:MNEMO_SERVICE_TOKEN', sources({ env: { MNEMO_SERVICE_TOKEN: ' from-env\n' } }))).toBe('from-env')
  })

  it('resolves `env:NAME` for a variable that is not set to empty, not to the literal', () => {
    // Falling back to the literal would send "env:MISSING" as a bearer token and
    // produce a 401 that reads like a wrong token rather than a wrong config.
    expect(resolveToken('env:MISSING', sources())).toBe('')
  })

  it('reads `file:/path` and trims the trailing newline', () => {
    expect(resolveToken('file:/etc/mnemo/token', sources({ readFile: () => 'abc123\n' }))).toBe('abc123')
  })

  it('rejects a malformed reference instead of silently sending it', () => {
    expect(() => resolveToken('env:', sources())).toThrow(/needs a variable name/)
    expect(() => resolveToken('file:', sources())).toThrow(/needs a path/)
  })

  it('reports an unreadable file by path', () => {
    expect(() => resolveToken('file:/nope/token', sources())).toThrow(/cannot read serviceToken from \/nope\/token/)
  })
})
