/** 唯一的请求出口：CSRF、401 回调、错误归一都在这里，页面里不再各写一遍 fetch。 */

export class ApiError extends Error {
  readonly status: number
  readonly data: Record<string, unknown> | null

  constructor(message: string, status: number, data: Record<string, unknown> | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

let csrfToken = ''
let unauthorizedHandler: ((message: string) => void) | null = null

export function setCsrf(token: string | undefined | null): void {
  csrfToken = token || ''
}

export function onUnauthorized(handler: (message: string) => void): void {
  unauthorizedHandler = handler
}

export interface ApiOptions {
  /** 登录失败也是 401，这条路径不该把已登录用户踢回登录页 */
  silent401?: boolean
}

export async function api<T>(path: string, init: RequestInit = {}, options: ApiOptions = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const method = (init.method || 'GET').toUpperCase()
  if (init.body && !headers.has('content-type')) headers.set('content-type', 'application/json')
  if (csrfToken && method !== 'GET' && method !== 'HEAD') headers.set('X-CSRF-Token', csrfToken)

  const res = await fetch('/admin/api' + path, {
    credentials: 'same-origin',
    ...init,
    headers,
  })

  const text = await res.text()
  let data: Record<string, unknown> | null = null
  if (text) {
    try {
      data = JSON.parse(text) as Record<string, unknown>
    } catch {
      data = { error: text }
    }
  }

  if (res.status === 401) {
    csrfToken = ''
    if (!options.silent401) {
      const msg = (data && (data.error as string)) || '会话已过期，请重新登录'
      unauthorizedHandler?.(msg)
    }
    throw new ApiError('unauthorized', 401, data)
  }

  if (!res.ok) {
    const raw = data && (data.error || data.message)
    throw new ApiError(typeof raw === 'string' ? raw : `HTTP ${res.status}`, res.status, data)
  }

  return data as T
}
