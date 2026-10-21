import { reactive, ref } from 'vue'
import { api } from './api'
import type { Overview } from '../types'

/** 会话与全局视图状态。控制台是单用户、单页面，一个模块级 store 足够，不必引 Pinia。 */
export const session = reactive({
  username: '',
  sessionHours: 12,
  consoleEnabled: true,
})

/** 概览数据被概览页和顶栏（向量空间 chip）共用，取一次即可。 */
export const overview = reactive<{ data: Overview | null; loading: boolean }>({
  data: null,
  loading: false,
})

/** 顶栏「刷新」按钮：各页 watch 它，避免把刷新逻辑塞进布局组件。 */
export const refreshTick = ref(0)

export function requestRefresh(): void {
  refreshTick.value += 1
}

/** 会话失效 / 主动登出 / 改完账号口令需要重登，都走这两个计数器，由根组件统一处理。 */
export const authEvents = reactive({ logout: 0, relogin: 0, hint: '' })

export function forceLogout(hint = ''): void {
  authEvents.hint = hint
  authEvents.logout += 1
}

export function requestRelogin(hint = ''): void {
  authEvents.hint = hint
  authEvents.relogin += 1
}

export async function loadOverview(): Promise<Overview | null> {
  overview.loading = true
  try {
    const data = await api<Overview>('/overview')
    overview.data = data
    return data
  } finally {
    overview.loading = false
  }
}
