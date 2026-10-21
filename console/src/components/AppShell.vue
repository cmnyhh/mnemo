<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NTag } from 'naive-ui'
import { api, setCsrf } from '../lib/api'
import { forceLogout, loadOverview, overview, requestRefresh } from '../lib/store'
import { toastError } from '../lib/feedback'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '控制台')
const space = computed(() => overview.data?.space)
const dirty = computed(() => Boolean(overview.data?.requiresReindex))
const version = computed(() => overview.data?.service?.version || '—')

const navItems = [
  { to: '/overview', label: '概览' },
  { to: '/records', label: '记忆库' },
  { to: '/recall', label: '召回测试' },
  { to: '/config', label: '配置' },
  { to: '/tokens', label: '令牌' },
  { to: '/observability', label: '观测' },
  { to: '/system', label: '系统' },
]

async function logout(): Promise<void> {
  try {
    await api('/logout', { method: 'POST' })
  } catch {
    // 会话本来就已经失效，照样回登录页
  }
  setCsrf('')
  forceLogout('已退出登录')
}

onMounted(() => {
  loadOverview().catch((e: Error) => toastError(e.message))
})
</script>

<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <div class="mark">M</div>
        <div>
          <h1>mnemo</h1>
          <p>控制台</p>
        </div>
      </div>

      <nav class="nav">
        <router-link v-for="item in navItems" :key="item.to" :to="item.to">
          <span class="dot"></span>{{ item.label }}
        </router-link>
      </nav>

      <div class="side-foot">
        <span>v{{ version }}</span>
        <span class="mono">{{ space?.spaceId || '—' }}</span>
      </div>
    </aside>

    <div class="main">
      <div class="topbar">
        <h2>{{ title }}</h2>
        <n-tag v-if="space" size="small" :bordered="false" round>
          {{ space.model || '—' }} · {{ space.dims || '—' }} 维 · {{ space.provider || '—' }}
        </n-tag>
        <n-tag v-if="dirty" size="small" type="warning" round>待重建索引</n-tag>
        <span class="grow"></span>
        <n-button size="small" quaternary @click="requestRefresh">刷新</n-button>
        <n-button size="small" @click="logout">退出</n-button>
      </div>

      <div class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </div>
    </div>
  </div>
</template>
