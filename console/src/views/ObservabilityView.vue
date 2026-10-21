<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NCard, NDataTable, NSelect, NSpace, NTag } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { toastError } from '../lib/feedback'
import { fmtTime, num } from '../lib/format'
import { refreshTick } from '../lib/store'
import type { AuditEntry, AuditList, MetricsSnapshot, StageStat } from '../types'

const metrics = ref<MetricsSnapshot | null>(null)
const audit = ref<AuditList | null>(null)
const loading = ref(false)
const auditLoading = ref(false)
const action = ref('')

/** 召回总次数按「结局」相加：hit + abstain + empty + error，比单独取 timer.count 更全。 */
const calls = computed(() => Object.values(metrics.value?.outcomes ?? {}).reduce((a, b) => a + b, 0))
const hits = computed(() => metrics.value?.outcomes?.hit ?? 0)
const hitRate = computed(() => (calls.value ? Math.round((hits.value * 1000) / calls.value) / 10 : 0))

const actionOptions = computed(() => [
  { label: '全部动作', value: '' },
  ...(audit.value?.actions ?? []).map((a) => ({ label: actionLabel(a), value: a })),
])

const outcomeOptions = computed(() => [
  { label: '命中', value: 'hit' },
  { label: '弃权', value: 'abstain' },
  { label: '无候选', value: 'empty' },
  { label: '出错', value: 'error' },
])

const stageColumns: DataTableColumns<StageStat> = [
  { title: '链路', key: 'kind', width: 80, render: (r) => (r.kind === 'search' ? '召回' : '写入') },
  { title: '阶段', key: 'stage', render: (r) => stageLabel(r.stage) },
  { title: '次数', key: 'count', width: 90, align: 'right', render: (r) => num(r.count) },
  { title: 'p50', key: 'p50Ms', width: 90, align: 'right', render: (r) => ms(r.p50Ms) },
  { title: 'p95', key: 'p95Ms', width: 90, align: 'right', render: (r) => ms(r.p95Ms) },
  { title: '最大', key: 'maxMs', width: 90, align: 'right', render: (r) => ms(r.maxMs) },
]

const auditColumns: DataTableColumns<AuditEntry> = [
  { title: '时间', key: 'at', width: 150, render: (r) => fmtTime(r.at) },
  { title: '动作', key: 'action', width: 110, render: (r) => actionLabel(r.action) },
  {
    title: '对象',
    key: 'target',
    render: (r) => (r.target ? r.target : '—'),
    ellipsis: { tooltip: true },
  },
  {
    title: '结果',
    key: 'outcome',
    width: 90,
    render: (r) =>
      h(NTag, { size: 'small', bordered: false, type: outcomeType(r.outcome) }, { default: () => r.outcome }),
  },
  { title: '来源 IP', key: 'ip', width: 130, render: (r) => r.ip || '—' },
]

function ms(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return `${Number(v).toFixed(1)} ms`
}

function stageLabel(stage: string): string {
  const map: Record<string, string> = {
    embed: '嵌入（公网模型）',
    bm25: 'BM25（PG）',
    dense: '向量（Qdrant）',
    rerank: '精排',
    extract: '抽取（LLM）',
  }
  return map[stage] || stage
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    'console.login': '登录',
    'console.logout': '退出',
    'console.credentials': '改账号口令',
    'config.update': '改配置',
    'index.reindex': '重建索引',
    'storage.cleanup': '清理向量',
    'record.forget': '遗忘记忆',
    'record.pin': '钉住',
    'record.unpin': '取消钉住',
    'tier.rebalance': '分层治理',
    'token.create': '签发令牌',
    'token.revoke': '撤销令牌',
    'token.delete': '删除令牌',
  }
  return map[action] || action
}

function outcomeTag(value: string): 'success' | 'warning' | 'error' | 'default' {
  if (value === 'hit') return 'success'
  if (value === 'abstain') return 'warning'
  if (value === 'error') return 'error'
  return 'default'
}

function outcomeType(outcome: string): 'success' | 'warning' | 'error' | 'default' {
  if (outcome === 'ok') return 'success'
  if (outcome === 'fail' || outcome === 'error') return 'error'
  if (outcome === 'throttled' || outcome === 'not-found') return 'warning'
  return 'default'
}

async function loadMetrics(): Promise<void> {
  loading.value = true
  try {
    metrics.value = await api<MetricsSnapshot>('/metrics')
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function loadAudit(): Promise<void> {
  auditLoading.value = true
  try {
    const q = action.value ? `&action=${encodeURIComponent(action.value)}` : ''
    audit.value = await api<AuditList>(`/audit?limit=50${q}`)
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    auditLoading.value = false
  }
}

function refresh(): void {
  void loadMetrics()
  void loadAudit()
}

onMounted(refresh)
watch(refreshTick, refresh)
watch(action, () => void loadAudit())
</script>

<template>
  <div class="cards">
    <div class="stat">
      <div class="k">召回 p95</div>
      <div class="v">{{ ms(metrics?.search.p95Ms) }}</div>
      <div class="sub">共 {{ num(metrics?.search.count) }} 次 · 最大 {{ ms(metrics?.search.maxMs) }}</div>
    </div>
    <div class="stat">
      <div class="k">召回 p50</div>
      <div class="v">{{ ms(metrics?.search.p50Ms) }}</div>
      <div class="sub">p99 {{ ms(metrics?.search.p99Ms) }}</div>
    </div>
    <div class="stat">
      <div class="k">写入 p95</div>
      <div class="v">{{ ms(metrics?.ingest.p95Ms) }}</div>
      <div class="sub">共 {{ num(metrics?.ingest.count) }} 次 · 最大 {{ ms(metrics?.ingest.maxMs) }}</div>
    </div>
    <div class="stat">
      <div class="k">命中率</div>
      <div class="v">{{ hitRate }}%</div>
      <div class="sub">{{ num(hits) }} 命中 / {{ num(calls) }} 次调用</div>
    </div>
  </div>

  <n-card size="small" title="分阶段耗时" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button size="tiny" :loading="loading" @click="loadMetrics">刷新</n-button>
    </template>
    <n-data-table
      v-if="metrics?.stages?.length"
      :columns="stageColumns"
      :data="metrics.stages"
      :bordered="false"
      size="small"
      :row-key="(row: StageStat) => `${row.kind}-${row.stage}`"
    />
    <div v-else class="faint">还没有请求。跑一次召回测试，这里就会出现各段耗时。</div>
    <div class="help">
      按 p95 从高到低排。哪一段最贵，慢就出在哪：嵌入与精排走公网，BM25 与向量在本地。
    </div>
  </n-card>

  <n-card size="small" title="结果分布" style="margin-bottom: 16px">
    <n-space vertical size="small">
      <n-space align="center">
        <span class="muted" style="width: 76px">召回结果</span>
        <n-tag
          v-for="opt in outcomeOptions"
          :key="opt.value"
          size="small"
          :bordered="false"
          :type="outcomeTag(opt.value)"
        >
          {{ opt.label }} {{ num(metrics?.outcomes?.[opt.value] ?? 0) }}
        </n-tag>
      </n-space>
      <n-space align="center">
        <span class="muted" style="width: 76px">精排</span>
        <n-tag size="small" :bordered="false">
          生效 {{ num(metrics?.rerank?.applied ?? 0) }}
        </n-tag>
        <n-tag size="small" :bordered="false">
          跳过 {{ num(metrics?.rerank?.skipped ?? 0) }}
        </n-tag>
        <n-tag size="small" :bordered="false" :type="(metrics?.rerank?.failed ?? 0) > 0 ? 'warning' : 'default'">
          失败 {{ num(metrics?.rerank?.failed ?? 0) }}
        </n-tag>
      </n-space>
      <n-space align="center">
        <span class="muted" style="width: 76px">降级</span>
        <n-tag size="small" :bordered="false">向量 {{ num(metrics?.degrade?.vector ?? 0) }}</n-tag>
        <n-tag size="small" :bordered="false">精排 {{ num(metrics?.degrade?.rerank ?? 0) }}</n-tag>
        <n-tag size="small" :bordered="false">抽取 {{ num(metrics?.degrade?.extract ?? 0) }}</n-tag>
      </n-space>
      <n-space align="center">
        <span class="muted" style="width: 76px">写入审查</span>
        <n-tag size="small" :bordered="false">放行 {{ num(metrics?.review?.pass ?? 0) }}</n-tag>
        <n-tag size="small" :bordered="false">降级 {{ num(metrics?.review?.downgrade ?? 0) }}</n-tag>
      </n-space>
    </n-space>
    <div class="help">
      弃权持续升高通常不是「没有记忆」，而是精排阈值偏严或索引与模型不一致。
    </div>
  </n-card>

  <n-card size="small" title="审计日志">
    <template #header-extra>
      <n-space align="center" size="small">
        <n-select
          v-model:value="action"
          :options="actionOptions"
          size="tiny"
          style="width: 140px"
          :consistent-menu-width="false"
        />
        <n-button size="tiny" :loading="auditLoading" @click="loadAudit">刷新</n-button>
      </n-space>
    </template>

    <n-data-table
      v-if="audit?.items?.length"
      :columns="auditColumns"
      :data="audit.items"
      :bordered="false"
      size="small"
      :row-key="(row: AuditEntry) => row.id"
    />
    <div v-else class="faint">还没有记录。改一次配置或钉住一条记忆，就会出现在这里。</div>

    <div class="help">
      保留 {{ audit?.retentionDays ?? 180 }} 天，只记「谁动了什么」，不记记忆正文与密钥。
    </div>

    <n-alert type="default" :bordered="false" style="margin-top: 12px">
      要给 Prometheus 抓取，用带 Bearer 令牌的地址 <span class="mono">{{ metrics?.scrapePath }}</span>；
      四个核心指标是 <span class="mono">mnemo_search</span>、
      <span class="mono">mnemo_search_stage</span>、<span class="mono">mnemo_ingest</span>、
      <span class="mono">mnemo_search_outcome</span>。
    </n-alert>
  </n-card>
</template>
