<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NForm,
  NFormItem,
  NGrid,
  NGridItem,
  NInput,
  NProgress,
  NSpace,
  NTag,
} from 'naive-ui'
import { api } from '../lib/api'
import { confirmAction, toast, toastError } from '../lib/feedback'
import { fmtBytes, fmtMb, fmtTime, num, uptimeText } from '../lib/format'
import { reindex, startReindex, syncReindex } from '../lib/reindex'
import { loadOverview, overview, refreshTick, requestRelogin, session } from '../lib/store'
import type { CleanupResult, StorageInfo } from '../types'

const username = ref(session.username)
const oldPassword = ref('')
const newPassword = ref('')
const savingAccount = ref(false)
const starting = ref(false)
const storage = ref<StorageInfo | null>(null)
const cleaning = ref(false)

const service = computed(() => overview.data?.service)
const space = computed(() => overview.data?.space)
const pg = computed(() => storage.value?.postgres ?? {})
const vec = computed(() => storage.value?.vectors ?? {})
const assets = computed(() => storage.value?.assets ?? {})
const disk = computed(() => storage.value?.disk ?? {})
const reindexState = computed(() => reindex.state)
const percent = computed(() => {
  const s = reindex.state
  if (!s || !s.total) return s?.status === 'done' ? 100 : 0
  return Math.min(100, Math.round((s.processed * 1000) / s.total) / 10)
})

async function refresh(): Promise<void> {
  try {
    const d = await loadOverview()
    syncReindex(d?.reindex)
  } catch (e) {
    toastError((e as Error).message)
  }
}

async function loadStorage(): Promise<void> {
  try {
    storage.value = await api<StorageInfo>('/storage')
  } catch (e) {
    toastError((e as Error).message)
  }
}

async function cleanupVectors(): Promise<void> {
  const retained = vec.value.retained ?? 0
  const ok = await confirmAction(
    `清理 ${num(retained)} 条不再参与召回的向量副本？记忆条目本身保留，之后仍可在「记忆库」里翻到。`,
    '清理',
  )
  if (!ok) return
  cleaning.value = true
  try {
    const r = await api<CleanupResult>('/storage/cleanup', { method: 'POST' })
    const n = num(r.pointsRemoved ?? r.deleted)
    toast(r.failed ? `已清理 ${n} 条向量副本，${num(r.failed)} 条失败` : `已清理 ${n} 条向量副本`)
    await loadStorage()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    cleaning.value = false
  }
}

async function saveAccount(): Promise<void> {
  if (!oldPassword.value) {
    toastError('请填写原口令')
    return
  }
  if (newPassword.value && newPassword.value.length < 8) {
    toastError('新口令至少 8 位')
    return
  }
  savingAccount.value = true
  try {
    await api('/credentials', {
      method: 'POST',
      body: JSON.stringify({
        username: username.value.trim(),
        oldPassword: oldPassword.value,
        newPassword: newPassword.value,
      }),
    })
    oldPassword.value = ''
    newPassword.value = ''
    requestRelogin('账号或口令已修改，请重新登录')
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    savingAccount.value = false
  }
}

async function reindexNow(): Promise<void> {
  const ok = await confirmAction(
    '重建会先删除现有向量集合，再按当前配置把所有 active 记录重新嵌入。期间向量召回不可用（BM25 不受影响）。继续？',
    '开始重建',
  )
  if (!ok) return
  starting.value = true
  try {
    await startReindex()
    toast('已开始重建索引')
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    starting.value = false
  }
}

onMounted(() => {
  void refresh()
  void loadStorage()
})
watch(refreshTick, () => {
  void refresh()
  void loadStorage()
})
watch(
  () => reindex.finishedTick,
  () => {
    void refresh()
    void loadStorage()
  },
)
</script>

<template>
  <n-card size="small" title="运行信息" style="margin-bottom: 16px">
    <n-descriptions :column="1" size="small" label-placement="left" :label-style="{ width: '104px' }">
      <n-descriptions-item label="服务">
        {{ service?.name || 'mnemo' }} v{{ service?.version || '—' }}
      </n-descriptions-item>
      <n-descriptions-item label="启动时间">
        {{ fmtTime(service?.startedAt) }}（已运行 {{ uptimeText(service?.uptimeSec) }}）
      </n-descriptions-item>
      <n-descriptions-item label="JVM 堆">
        {{ fmtMb(service?.heapUsedMb) }} / {{ fmtMb(service?.heapMaxMb) }}
      </n-descriptions-item>
      <n-descriptions-item label="Java">{{ service?.javaVersion || '—' }}</n-descriptions-item>
      <n-descriptions-item label="租户">{{ service?.tenant || '—' }}</n-descriptions-item>
      <n-descriptions-item label="向量空间">{{ space?.spaceId || '—' }}</n-descriptions-item>
      <n-descriptions-item label="集合">{{ overview.data?.vector?.collection || '—' }}</n-descriptions-item>
      <n-descriptions-item label="控制台账号">{{ session.username || '—' }}</n-descriptions-item>
      <n-descriptions-item label="控制台会话">
        {{ overview.data?.adminSessions ?? '—' }}
      </n-descriptions-item>
    </n-descriptions>
  </n-card>

  <n-card size="small" title="向量索引" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button
        size="tiny"
        :loading="starting"
        :disabled="reindexState?.status === 'running'"
        @click="reindexNow"
      >
        重建索引
      </n-button>
    </template>

    <div v-if="!reindexState || !reindexState.status || reindexState.status === 'idle'" class="faint">
      还没有重建过。向量可以随时从记忆库重算。
    </div>

    <template v-else>
      <n-space align="center" style="margin-bottom: 10px">
        <n-tag
          size="small"
          :type="reindexState.status === 'running' ? 'info' : reindexState.status === 'done' ? 'success' : 'error'"
          :bordered="false"
        >
          {{ reindexState.status }}
        </n-tag>
        <span class="muted">{{ num(reindexState.processed) }} / {{ num(reindexState.total) }} 条</span>
        <span class="grow"></span>
        <span class="mono muted">{{ percent }}%</span>
      </n-space>
      <n-progress type="line" :percentage="percent" :show-indicator="false" :height="8" />
      <div class="help">
        成功 {{ num(reindexState.embedded) }} · 失败 {{ num(reindexState.failed) }}
        <span v-if="reindexState.message">· {{ reindexState.message }}</span>
        <span v-if="reindexState.startedAt">· 开始于 {{ fmtTime(reindexState.startedAt) }}</span>
      </div>
    </template>
  </n-card>

  <n-card size="small" title="存储" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button
        size="tiny"
        :loading="cleaning"
        :disabled="!vec.retained"
        @click="cleanupVectors"
      >
        清理归档向量
      </n-button>
    </template>

    <n-descriptions :column="1" size="small" label-placement="left" :label-style="{ width: '104px' }">
      <n-descriptions-item label="记忆条目">
        共 {{ num(pg.items) }} 条（生效 {{ num(pg.active) }} · 保留 {{ num(pg.retained) }}）
      </n-descriptions-item>
      <n-descriptions-item label="PostgreSQL">{{ fmtBytes(pg.bytes) }}</n-descriptions-item>
      <n-descriptions-item label="向量副本">
        {{ num(vec.points) }} 条 · 约 {{ fmtBytes(vec.bytes) }}
        <span class="faint">（其中 {{ num(vec.retained) }} 条不参与召回）</span>
      </n-descriptions-item>
      <n-descriptions-item label="附件">
        {{ num(assets.files) }} 个 · {{ fmtBytes(assets.bytes) }}
      </n-descriptions-item>
      <n-descriptions-item label="磁盘可用">
        {{ fmtBytes(disk.usable) }} / {{ fmtBytes(disk.total) }}
      </n-descriptions-item>
    </n-descriptions>

    <div class="help">
      被取代或已遗忘的记忆默认保留，仍可在「记忆库」按状态翻到；清理只删它们的向量副本，不动条目。
    </div>
  </n-card>

  <n-card size="small" title="控制台账号" style="margin-bottom: 16px">
    <n-form label-placement="top" style="max-width: 640px">
      <n-grid cols="1 640:2" :x-gap="20">
        <n-grid-item>
          <n-form-item label="账号">
            <n-input v-model:value="username" placeholder="3–32 位，字母/数字/点/下划线/短横线" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="原口令">
            <n-input
              v-model:value="oldPassword"
              type="password"
              show-password-on="click"
              :input-props="{ autocomplete: 'current-password' }"
            />
          </n-form-item>
        </n-grid-item>
        <n-grid-item :span="2">
          <n-form-item label="新口令（至少 8 位，留空表示不改）">
            <n-input
              v-model:value="newPassword"
              type="password"
              show-password-on="click"
              :input-props="{ autocomplete: 'new-password' }"
            />
          </n-form-item>
        </n-grid-item>
      </n-grid>
    </n-form>

    <n-button type="primary" :loading="savingAccount" @click="saveAccount">保存</n-button>
    <div class="help">保存后需要重新登录。</div>
  </n-card>

  <n-card size="small" title="记忆 API">
    <n-descriptions :column="1" size="small" label-placement="left" :label-style="{ width: '104px' }">
      <n-descriptions-item label="接口前缀"><span class="mono">/v1/memory</span></n-descriptions-item>
      <n-descriptions-item label="鉴权方式">
        <span class="mono">Authorization: Bearer &lt;token&gt;</span>
      </n-descriptions-item>
      <n-descriptions-item label="健康检查">
        <span class="mono">GET /v1/memory/health · /v1/memory/readyz</span>
      </n-descriptions-item>
    </n-descriptions>
    <n-alert type="default" :bordered="false" style="margin-top: 12px">
      插件用独立令牌调用，控制台停用不影响召回与写入。
    </n-alert>
  </n-card>
</template>
