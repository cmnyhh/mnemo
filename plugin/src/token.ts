/**
 * Resolve the configured bearer token to its literal value.
 *
 * A bundle ships its own patch file, so a literal token in that file would be
 * copied into every installation — and into version control — the moment the
 * package is published. The value is therefore allowed to be an indirection
 * resolved at load: `env:NAME` reads that variable, `file:/path` reads that file.
 * Anything else is the token itself, which keeps a hand-written one-off patch
 * working exactly as before.
 * @module @deepseek-ai/dsh-memory-core/token
 */

import { readFileSync } from 'node:fs'

/** Where an indirection reads from; injected so the resolution is testable. */
export interface TokenSources {
  env: Record<string, string | undefined>
  readFile: (path: string) => string
}

/** Default sources: the process environment and the local filesystem. */
export const defaultTokenSources: TokenSources = {
  env: process.env,
  readFile: path => readFileSync(path, 'utf8'),
}

/**
 * Turn a configured value into the token to send.
 * @param raw - configured `serviceToken`, possibly an `env:` or `file:` reference.
 * @param sources - where indirections read from.
 * @returns the token, empty when the indirection points at nothing.
 * @throws when a reference is malformed or its file cannot be read — a token
 * that silently resolves to empty would degrade into 401s on every request.
 */
export function resolveToken(raw: string | undefined, sources: TokenSources = defaultTokenSources): string {
  const value = (raw ?? '').trim()
  if (value.startsWith('env:')) {
    const name = value.slice('env:'.length).trim()
    if (name.length === 0) {
      throw new Error('memory-core: serviceToken "env:" needs a variable name')
    }
    return (sources.env[name] ?? '').trim()
  }
  if (value.startsWith('file:')) {
    const path = value.slice('file:'.length).trim()
    if (path.length === 0) {
      throw new Error('memory-core: serviceToken "file:" needs a path')
    }
    try {
      return sources.readFile(path).trim()
    } catch (error) {
      throw new Error(`memory-core: cannot read serviceToken from ${path}: ${(error as Error).message}`)
    }
  }
  return value
}
