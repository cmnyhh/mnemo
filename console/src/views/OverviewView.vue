<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { loadOverview, overview, refreshTick } from '../lib/store'
import { fmtMb, fmtTime, num, uptimeText } from '../lib/format'
import { toastError } from '../lib/feedback'
import { syncReindex } from '../lib/reindex'
import type { Dependencies, SessionRow } from '../types'

const deps = ref<Dependencies | null>(null)
const probing = ref(false)
const loading = ref(false)

const cards = computed(() => {
  const d = overview.data
  if (!d) return []
  const stats = d.stats || {}
  const byKind = stats.byKind || []
  const byTier = stats.byTier || []
  const media = d.media
  const vec = d.vector || {}
  const svc = d.service
  return [
    {
      k: '记录总数',
      v: num(stats.total),
      sub: stats.active !== undefined ? `${num(stats.active)} 条 active` : '',
    },
    {
      k: '记忆种类',
      v: String(byKind.length),
      sub: byKind.map((x) => `${x.kind} ${x.count}`).join(' · ') || '尚无记录',
    },
    { k: '会话数', v: num(stats.sessions), sub: '跨会话累计' },
    {
      k: '常驻记忆',
      v: num(byTier.find((x) => x.tier === 'T0')?.count ?? 0),
      sub:
        byTier.map((x) => `${x.tier} ${x.count}`).join(' · ') ||
        '常驻 / 工作集 / 长期 / 归档',
    },
    {
      k: '附件',
      v: media ? num(media.assets) : '—',
      sub:
        media && media.byKind
          ? Object.entries(media.byKind)
              .map(([k, n]) => `${k} ${n}`)
              .join(' · ') || '尚无附件'
          : '图片 / 音频 / 视频 / 文档',
    },
    {
      k: '向量点',
      v: vec.points !== undefined && (vec.points as number) >= 0 ? num(vec.points) : '—',
      sub: vec.dims ? `${vec.dims} 维 · ${vec.collection || ''}` : vec.error ? '集合不可用' : '—',
    },
    {
      k: '服务运行',
      v: uptimeText(svc?.uptimeSec),
      sub: `v${svc?.version || '—'} · 堆 ${fmtMb(svc?.heapUsedMb)}`,
    },
  ]
})

const sessions = computed<SessionRow[]>(() => overview.data?.recentSessions || [])

const sessionColumns: DataTableColumns<SessionRow> = [
  { title: '会话', key: 'sessionId', render: (row) => h('span', { class: 'mono' }, row.sessionId) },
  { title: '记录', key: 'count', width: 90, render: (row) => h('span', { class: 'mono' }, num(row.count)) },
  {
    title: '最近写入',
    key: 'lastAt',
    width: 180,
    render: (row) => h('span', { class: 'muted' }, fmtTime(row.lastAt)),
  },
]

async function refresh(): Promise<void> {
  loading.value = true
  try {
    const d = await loadOverview()
    syncReindex(d?.reindex)
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function probe(): Promise<void> {
  probing.value = true
  try {
    deps.value = await api<Dependencies>('/dependencies?refresh=1')
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    probing.value = false
  }
}

onMounted(refresh)
watch(refreshTick, refresh)
</script>

<template>
  <div class="cards">
    <div v-for="card in cards" :key="card.k" class="stat">
      <div class="k">{{ card.k }}</div>
      <div class="v">{{ card.v }}</div>
      <div class="sub">{{ card.sub }}</div>
    </div>
  </div>

  <n-card size="small" title="运行状态" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button size="tiny" :loading="probing" @click="probe">检测连接</n-button>
    </template>

    <n-descriptions :column="1" size="small" label-placement="left" :label-style="{ width: '96px' }">
      <n-descriptions-item label="数据库">
        <template v-if="deps">
          <n-tag size="small" :type="deps.db.ok ? 'success' : 'error'" :bordered="false">
            {{ deps.db.ok ? '正常' : '异常' }}
          </n-tag>
          <span class="muted" style="margin-left: 8px">{{ deps.db.ok ? '连接正常' : deps.db.error }}</span>
        </template>
        <span v-else class="faint">尚未检测</span>
      </n-descriptions-item>

      <n-descriptions-item label="Qdrant">
        <template v-if="overview.data?.vector?.error">
          <n-tag size="small" type="error" :bordered="false">异常</n-tag>
          <span class="muted" style="margin-left: 8px">{{ overview.data.vector.error }}</span>
        </template>
        <template v-else>
          <n-tag size="small" type="success" :bordered="false">正常</n-tag>
          <span class="muted" style="margin-left: 8px">
            {{ num(overview.data?.vector?.points) }} 个点 · {{ overview.data?.vector?.dims ?? '?' }} 维
          </span>
        </template>
      </n-descriptions-item>

      <n-descriptions-item label="Embedding">
        <template v-if="deps">
          <n-tag size="small" :type="deps.embedding.ok ? 'success' : 'error'" :bordered="false">
            {{ deps.embedding.ok ? '正常' : '异常' }}
          </n-tag>
          <span class="muted" style="margin-left: 8px">
            <template v-if="deps.embedding.ok">
              {{ deps.embedding.provider }} · {{ deps.embedding.model }} · 实测
              {{ deps.embedding.actualDims }} 维 · {{ deps.embedding.latencyMs }} ms
            </template>
            <template v-else>{{ deps.embedding.error }}</template>
          </span>
        </template>
        <span v-else class="faint">
          尚未检测（当前 {{ overview.data?.space?.provider || '—' }} / {{ overview.data?.space?.model || '—' }}）
        </span>
      </n-descriptions-item>
    </n-descriptions>
  </n-card>

  <n-card size="small" title="最近会话">
    <n-data-table
      :columns="sessionColumns"
      :data="sessions"
      :loading="loading"
      :bordered="false"
      size="small"
      :row-key="(row: SessionRow) => row.sessionId"
    />
    <div v-if="!loading && sessions.length === 0" class="faint" style="padding: 12px 2px">
      还没有记录。
    </div>
  </n-card>
</template>
