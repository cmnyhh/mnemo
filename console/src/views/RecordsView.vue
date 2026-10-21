<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NInput,
  NSelect,
  NSpace,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { refreshTick } from '../lib/store'
import { confirmAction, toast, toastError } from '../lib/feedback'
import { fmtTime, num, truncate } from '../lib/format'
import type { AdminRecord, RecordsPage, SessionRow } from '../types'

const query = ref('')
const sessionId = ref<string>('')
const status = ref<string>('')
const tier = ref<string>('')
const items = ref<AdminRecord[]>([])
const total = ref(0)
const page = ref(1)
const pages = ref(0)
const pageSize = 30
const loading = ref(false)
const sessions = ref<SessionRow[]>([])
const detail = ref<AdminRecord | null>(null)
const drawerOpen = ref(false)
const forgetting = ref(false)
const pinning = ref(false)
const rebalancing = ref(false)

const tierOptions = [
  { label: '全部层级', value: '' },
  { label: 'T0 常驻', value: 'T0' },
  { label: 'T1 工作集', value: 'T1' },
  { label: 'T2 长期', value: 'T2' },
  { label: 'T3 归档', value: 'T3' },
]

const tierHelp: Record<string, string> = {
  T0: '每轮注入',
  T1: '本会话内有效',
  T2: '默认检索',
  T3: '默认不召回',
}

function tierType(tier?: string): 'warning' | 'info' | 'default' | 'error' {
  if (tier === 'T0') return 'warning'
  if (tier === 'T1') return 'info'
  if (tier === 'T3') return 'error'
  return 'default'
}

const sessionOptions = computed(() => [
  { label: '全部会话', value: '' },
  ...sessions.value.map((s) => ({ label: `${s.sessionId} (${s.count})`, value: s.sessionId })),
])

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '生效中', value: 'active' },
  { label: '已被取代', value: 'superseded' },
  { label: '已遗忘', value: 'deleted' },
]

function statusLabel(status: string): string {
  if (status === 'active') return '生效中'
  if (status === 'superseded') return '已被取代'
  if (status === 'deleted') return '已遗忘'
  return status
}

function tagType(status: string): 'success' | 'error' | 'warning' | 'default' {
  if (status === 'active') return 'success'
  if (status === 'deleted') return 'error'
  if (status === 'superseded') return 'warning'
  return 'default'
}

const columns: DataTableColumns<AdminRecord> = [
  {
    title: '时间',
    key: 'createdAt',
    width: 150,
    render: (row) => h('span', { class: 'mono faint' }, fmtTime(row.createdAt)),
  },
  {
    title: '会话',
    key: 'sessionId',
    width: 156,
    render: (row) =>
      h('span', { class: 'mono' }, [
        row.sessionId || '—',
        row.turn !== null && row.turn !== undefined ? h('span', { class: 'faint' }, ` T${row.turn}`) : '',
      ]),
  },
  {
    title: '类型',
    key: 'kind',
    width: 84,
    render: (row) => h(NTag, { size: 'small', bordered: false }, { default: () => row.kind }),
  },
  {
    title: '层级',
    key: 'tier',
    width: 76,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: tierType(row.tier), bordered: false },
        { default: () => row.tier || '—' },
      ),
  },
  {
    title: '内容',
    key: 'narrative',
    ellipsis: { tooltip: true },
    render: (row) => h('span', truncate(row.narrative, 160)),
  },
  {
    title: '状态',
    key: 'status',
    width: 96,
    render: (row) =>
      h(NTag, { size: 'small', type: tagType(row.status), bordered: false }, { default: () => statusLabel(row.status) }),
  },
]

const pagination = computed(() => ({
  page: page.value,
  pageSize,
  itemCount: total.value,
  showSizePicker: false,
  prefix: () =>
    `${num(total.value)} 条 · 第 ${pages.value === 0 ? 0 : page.value} / ${pages.value} 页`,
}))

async function loadSessions(): Promise<void> {
  try {
    const d = await api<{ items: SessionRow[] }>('/sessions')
    sessions.value = d.items || []
  } catch {
    sessions.value = []
  }
}

async function load(target?: number): Promise<void> {
  const next = target ?? page.value
  page.value = next
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(next), size: String(pageSize) })
    if (query.value.trim()) params.set('q', query.value.trim())
    if (sessionId.value) params.set('session', sessionId.value)
    if (status.value) params.set('status', status.value)
    if (tier.value) params.set('tier', tier.value)
    const d = await api<RecordsPage>(`/records?${params.toString()}`)
    items.value = d.items || []
    total.value = d.total
    pages.value = d.pages
    page.value = d.page
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function openDetail(row: AdminRecord): Promise<void> {
  try {
    const d = await api<{ record: AdminRecord }>(`/records/${row.id}`)
    detail.value = d.record
    drawerOpen.value = true
  } catch (e) {
    toastError((e as Error).message)
  }
}

async function forget(): Promise<void> {
  const record = detail.value
  if (!record) return
  const ok = await confirmAction(
    '遗忘后不再被召回，确定吗？',
    '遗忘',
  )
  if (!ok) return

  forgetting.value = true
  try {
    const res = await api<{ deleted: boolean; vectorDeleted: boolean; note?: string }>(
      `/records/${record.id}/forget`,
      { method: 'POST' },
    )
    toast(res.deleted ? '已遗忘' : '记录不存在')
    if (res.note) toastError(res.note)
    drawerOpen.value = false
    await Promise.all([load(), loadSessions()])
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    forgetting.value = false
  }
}

async function togglePin(): Promise<void> {
  const record = detail.value
  if (!record) return
  const next = record.tier !== 'T0'
  pinning.value = true
  try {
    const res = await api<{ updated: boolean; tier: string }>(`/records/${record.id}/pin`, {
      method: 'POST',
      body: JSON.stringify({ pinned: next }),
    })
    if (!res.updated) {
      toastError('记录不存在或已失效')
      return
    }
    detail.value = { ...record, tier: res.tier }
    toast(next ? '已钉住' : '已取消钉住')
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    pinning.value = false
  }
}

async function rebalance(): Promise<void> {
  rebalancing.value = true
  try {
    const r = await api<{
      demoted: number
      promoted: number
      archived: number
      revived: number
      synced: number
    }>('/tiers/rebalance', { method: 'POST' })
    toast(
      `整理完成：晋升 ${r.promoted} · 降级 ${r.demoted} · 归档 ${r.archived}`,
    )
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    rebalancing.value = false
  }
}

const rowProps = (row: AdminRecord) => ({
  style: 'cursor: pointer',
  onClick: () => {
    void openDetail(row)
  },
})

onMounted(() => {
  void loadSessions()
  void load(1)
})

watch(refreshTick, () => {
  void load()
})
</script>

<template>
  <n-card size="small">
    <n-space align="center" :size="10" :wrap="true" style="margin-bottom: 12px">
      <n-input
        v-model:value="query"
        placeholder="按内容搜索…"
        clearable
        style="width: 280px"
        @keyup.enter="load(1)"
        @clear="load(1)"
      />
      <n-select
        v-model:value="sessionId"
        :options="sessionOptions"
        style="width: 220px"
        @update:value="load(1)"
      />
      <n-select
        v-model:value="status"
        :options="statusOptions"
        style="width: 140px"
        @update:value="load(1)"
      />
      <n-select
        v-model:value="tier"
        :options="tierOptions"
        style="width: 130px"
        @update:value="load(1)"
      />
      <n-button type="primary" secondary @click="load(1)">查询</n-button>
      <n-button quaternary :loading="rebalancing" @click="rebalance">整理分层</n-button>
      <span class="grow"></span>
      <span class="faint">{{ num(total) }} 条命中</span>
    </n-space>

    <n-data-table
      remote
      :columns="columns"
      :data="items"
      :loading="loading"
      :pagination="pagination"
      :row-key="(row: AdminRecord) => row.id"
      :row-props="rowProps"
      :bordered="false"
      size="small"
      @update:page="load"
    />
  </n-card>

  <n-drawer v-model:show="drawerOpen" :width="640" placement="right">
    <n-drawer-content v-if="detail" title="记忆详情" closable>
      <n-space align="center" style="margin-bottom: 12px">
        <n-tag size="small" :type="tagType(detail.status)" :bordered="false">{{ statusLabel(detail.status) }}</n-tag>
        <span class="mono faint">{{ detail.id }}</span>
      </n-space>

      <n-descriptions :column="1" size="small" label-placement="left" :label-style="{ width: '104px' }">
        <n-descriptions-item label="会话 / 轮次">
          <span class="mono">
            {{ detail.sessionId || '—' }} / {{ detail.turn === null || detail.turn === undefined ? '—' : `T${detail.turn}` }}
          </span>
        </n-descriptions-item>
        <n-descriptions-item label="类型">
          {{ detail.kind }} · {{ detail.scope || '—' }}
        </n-descriptions-item>
        <n-descriptions-item label="层级">
          <n-tag size="small" :type="tierType(detail.tier)" :bordered="false">
            {{ detail.tier || '—' }}
          </n-tag>
          <span class="faint" style="margin-left: 8px">{{ tierHelp[detail.tier || ''] || '' }}</span>
        </n-descriptions-item>
        <n-descriptions-item label="写入时间">{{ fmtTime(detail.createdAt) }}</n-descriptions-item>
        <n-descriptions-item label="有效区间">
          {{ fmtTime(detail.validFrom) }} → {{ detail.validTo ? fmtTime(detail.validTo) : '现在' }}
        </n-descriptions-item>
        <n-descriptions-item label="被引用次数">{{ num(detail.accessCount) }}</n-descriptions-item>
        <n-descriptions-item label="字数">{{ num(detail.chars) }}</n-descriptions-item>
      </n-descriptions>

      <div class="faint" style="margin: 16px 0 6px">完整内容</div>
      <pre class="pre-block">{{ detail.narrative }}</pre>

      <template #footer>
        <n-space justify="space-between" style="width: 100%">
          <n-space :size="8">
            <n-button
              type="warning"
              secondary
              :loading="pinning"
              :disabled="detail.status !== 'active'"
              @click="togglePin"
            >
              {{ detail.tier === 'T0' ? '取消钉住' : '钉住' }}
            </n-button>
            <n-button
              type="error"
              secondary
              :loading="forgetting"
              :disabled="detail.status === 'deleted'"
              @click="forget"
            >
              遗忘
            </n-button>
          </n-space>
          <n-button @click="drawerOpen = false">关闭</n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>
