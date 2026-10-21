<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { NAlert, NSpin, useDialog, useMessage } from 'naive-ui'
import { useRouter } from 'vue-router'
import AppShell from './AppShell.vue'
import LoginView from '../views/LoginView.vue'
import { api, onUnauthorized, setCsrf } from '../lib/api'
import { bindFeedback } from '../lib/feedback'
import { authEvents, session } from '../lib/store'
import type { SessionInfo } from '../types'

type Phase = 'boot' | 'disabled' | 'login' | 'ready'

const phase = ref<Phase>('boot')
const hint = ref('')
const router = useRouter()

bindFeedback(useMessage(), useDialog())

// 任何一次 401 都说明服务端会话没了（重启、过期、别处改了口令），统一回登录页。
onUnauthorized((message) => {
  hint.value = message
  phase.value = 'login'
})

async function boot(): Promise<void> {
  try {
    const info = await api<SessionInfo>('/session')
    session.consoleEnabled = info.consoleEnabled
    session.sessionHours = info.sessionHours ?? 12
    if (!info.consoleEnabled) {
      phase.value = 'disabled'
      return
    }
    if (info.authenticated) {
      setCsrf(info.csrfToken)
      session.username = info.username || ''
      phase.value = 'ready'
      return
    }
    phase.value = 'login'
  } catch (e) {
    const message = e instanceof Error ? e.message : ''
    hint.value = message && message !== 'unauthorized' ? `无法连接服务：${message}` : '无法连接服务'
    phase.value = 'login'
  }
}

function onLoginSuccess(): void {
  hint.value = ''
  phase.value = 'ready'
  if (router.currentRoute.value.path !== '/overview') void router.replace('/overview')
}

watch(
  () => authEvents.logout,
  () => {
    hint.value = authEvents.hint
    phase.value = 'login'
  },
)

watch(
  () => authEvents.relogin,
  () => {
    hint.value = authEvents.hint
    phase.value = 'login'
  },
)

onMounted(() => {
  void boot()
})
</script>

<template>
  <div v-if="phase === 'boot'" class="gate">
    <n-spin size="small" />
  </div>

  <div v-else-if="phase === 'disabled'" class="gate">
    <div class="gate-card">
      <div class="gate-brand">
        <div class="mark">M</div>
        <div>
          <h1>mnemo 控制台</h1>
          <p>长期记忆 · 运维与配置</p>
        </div>
      </div>
      <n-alert type="warning" :bordered="false" title="控制台未启用">
        服务端没有配置控制台口令。请在服务器的 <code>.env</code> 里设置
        <code>MEMORY_ADMIN_PASSWORD</code>，然后重启服务。记忆 API 不受影响。
      </n-alert>
    </div>
  </div>

  <login-view v-else-if="phase === 'login'" :hint="hint" @success="onLoginSuccess" />

  <app-shell v-else />
</template>
