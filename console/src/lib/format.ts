export function num(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return Number(n).toLocaleString('zh-CN')
}

export function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** 列表里只关心时分，完整时间留给详情 */
export function fmtClock(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

export function uptimeText(sec: number | null | undefined): string {
  if (sec === null || sec === undefined) return '—'
  const s = Number(sec)
  if (s < 60) return `${Math.floor(s)} 秒`
  if (s < 3600) return `${Math.floor(s / 60)} 分`
  if (s < 86400) return `${Math.floor(s / 3600)} 时 ${Math.floor((s % 3600) / 60)} 分`
  return `${Math.floor(s / 86400)} 天 ${Math.floor((s % 86400) / 3600)} 时`
}

export function fmtMb(mb: number | null | undefined): string {
  return mb === null || mb === undefined ? '—' : `${mb} MB`
}

/** 字节转人读的容量。控制台的存储账目都走这里，避免每处各写一套除法。 */
export function fmtBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—'
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) return '—'
  if (n < 1024) return `${n} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i += 1
  }
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} ${units[i]}`
}

export function truncate(text: string | null | undefined, max: number): string {
  const s = text || ''
  return s.length > max ? s.slice(0, max) + '…' : s
}
