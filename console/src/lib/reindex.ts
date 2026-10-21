import { reactive } from 'vue'
import { api } from './api'
import { loadOverview } from './store'
import type { ReindexState } from '../types'

/**
 * 重建索引的进度是服务端进程内状态，页面切走再切回也要能接着看，
 * 所以轮询放在模块级而不是组件里——组件卸载不该中断一次正在跑的重建。
 */
export const reindex = reactive<{
  state: ReindexState | null
  polling: boolean
  finishedTick: number
}>({
  state: null,
  polling: false,
  finishedTick: 0,
})

let timer: number | null = null

export function syncReindex(state: ReindexState | null | undefined): void {
  if (!state) return
  reindex.state = state
  if (state.status === 'running') startPolling()
}

export async function startReindex(): Promise<ReindexState> {
  const s = await api<ReindexState>('/reindex', { method: 'POST' })
  reindex.state = s
  startPolling()
  return s
}

export function startPolling(): void {
  if (timer !== null) return
  reindex.polling = true
  timer = window.setInterval(() => {
    void tick()
  }, 1500)
}

export function stopPolling(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  reindex.polling = false
}

async function tick(): Promise<void> {
  try {
    const s = await api<ReindexState>('/reindex')
    reindex.state = s
    if (s.status !== 'running') {
      stopPolling()
      reindex.finishedTick += 1
      // 重建完，向量点数与「待重建」标记都变了
      await loadOverview().catch(() => null)
    }
  } catch {
    stopPolling()
  }
}
