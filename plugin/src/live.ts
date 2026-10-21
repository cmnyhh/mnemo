/**
 * Read a config field whose value may be replaced while the plugin runs.
 *
 * A `volatile` field arrives as a holder with `get()`, so the settings panel can
 * rewrite it without remounting the row. A plain field arrives as the value
 * itself — the schema still types it that way, which is why callers cannot see
 * the difference. Both shapes have to work: a deployment that never opens the
 * panel should behave exactly as before.
 * @module @deepseek-ai/dsh-memory-core/live
 */

/** The shape a volatile field arrives in. */
export interface VolatileLike<T> {
  get(): T | undefined
}

/**
 * Wrap a config field as a reader that always returns its current value.
 * @param value - the configured field, volatile holder or plain value.
 * @returns the reader; empty string when the field is unset or the holder empty.
 */
export function liveString(value: unknown): () => string {
  if (typeof value === 'string') return () => value.trim()
  if (typeof value === 'object' && value !== null && typeof (value as VolatileLike<unknown>).get === 'function') {
    return () => {
      const current = (value as VolatileLike<unknown>).get()
      return typeof current === 'string' ? current.trim() : ''
    }
  }
  return () => ''
}
