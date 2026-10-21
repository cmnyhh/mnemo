<script setup lang="ts">
import { computed, h, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NInput,
  NInputNumber,
  NProgress,
  NSelect,
  NSpace,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { toastError } from '../lib/feedback'
import { fmtClock } from '../lib/format'
import type { RecallHit, RecallResult } from '../types'

const EXAMPLES = ['端口是多少', '数据库用的什么版本', '许可证是什么', '日志路径在哪', '之前端口是多少']

const query = ref('')
const mode = ref('hybrid')
const topK = ref(6)
const running = ref(false)
const result = ref<RecallResult | null>(null)

const modeOptions = [
  { label: '混合', value: 'hybrid' },
  { label: '关键词', value: 'bm25' },
  { label: '语义', value: 'dense' },
]

const routeType = computed(() => {
  const route = result.value?.route
  if (route === 'dense') return 'success'
  if (route === 'bm25') return 'warning'
  return 'info'
})

const hits = computed<RecallHit[]>(() => result.value?.hits || [])

const columns: DataTableColumns<RecallHit> = [
  {
    title: '分数',
    key: 'score',
    width: 132,
    render: (row) =>
      h('div', { class: 'score-cell' }, [
        h('div', { class: 'bar' }, [
          h(NProgress, {
            type: 'line',
            percentage: Math.min(100, Math.max(0, row.score * 100)),
            showIndicator: false,
            height: 6,
            status: 'info',
          }),
        ]),
        h('span', { class: 'mono' }, row.score.toFixed(3)),
      ]),
  },
  {
    title: '写入',
    key: 'createdAt',
    width: 76,
    render: (row) => h('span', { class: 'mono muted' }, fmtClock(row.createdAt)),
  },
  { title: '内容', key: 'narrative', render: (row) => h('span', row.narrative) },
  {
    title: '名次',
    key: 'ranks',
    width: 110,
    render: (row) =>
      h('div', { class: 'mono faint', style: 'font-size: 11.5px; line-height: 1.6' }, [
        `BM25 ${row.bm25Rank ?? '—'}`,
        h('br'),
        `向量 ${row.denseRank ?? '—'}`,
      ]),
  },
]

async function run(): Promise<void> {
  const text = query.value.trim()
  if (!text) return
  running.value = true
  try {
    result.value = await api<RecallResult>('/recall', {
      method: 'POST',
      body: JSON.stringify({ query: text, mode: mode.value, topK: topK.value || 6 }),
    })
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    running.value = false
  }
}

function useExample(text: string): void {
  query.value = text
  void run()
}
</script>

<template>
  <n-card size="small" title="召回测试" style="margin-bottom: 16px">
    <template #header-extra>
      <span class="faint">关键词 + 语义 · 融合 · 精排</span>
    </template>

    <n-space align="center" :size="10" :wrap="true">
      <n-input
        v-model:value="query"
        placeholder="问一句，看它会召回什么"
        style="width: 360px"
        @keyup.enter="run"
      />
      <n-select v-model:value="mode" :options="modeOptions" style="width: 120px" />
      <n-input-number v-model:value="topK" :min="1" :max="50" style="width: 96px" />
      <n-button type="primary" :loading="running" @click="run">召回</n-button>
    </n-space>

    <n-space :size="8" style="margin-top: 12px">
      <n-tag
        v-for="example in EXAMPLES"
        :key="example"
        size="small"
        :bordered="false"
        style="cursor: pointer"
        @click="useExample(example)"
      >
        {{ example }}
      </n-tag>
    </n-space>
  </n-card>

  <template v-if="result">
    <n-card v-if="result.abstained || result.degradeReason" size="small" title="结果" style="margin-bottom: 16px">
      <template #header-extra>
        <n-space :size="8" align="center">
          <n-tag size="small" :type="routeType" :bordered="false" round>route {{ result.route }}</n-tag>
          <n-tag size="small" :bordered="false" round>{{ result.elapsedMs }} ms</n-tag>
          <n-tag
            size="small"
            :type="result.abstained ? 'warning' : 'success'"
            :bordered="false"
            round
          >
            {{ result.abstained ? '已弃权' : `${result.count} 条` }}
          </n-tag>
        </n-space>
      </template>

      <n-alert v-if="result.degradeReason" type="warning" :bordered="false" style="margin-bottom: 12px">
        {{ result.degradeReason }}
      </n-alert>

      <div v-if="result.abstained" class="faint">
        没有找到相关记忆，这一轮不会注入任何内容。
      </div>
      <div v-else-if="hits.length === 0" class="faint">没有任何命中。</div>
    </n-card>

    <n-card v-if="hits.length" size="small" title="命中" style="margin-bottom: 16px">
      <template #header-extra>
        <n-space :size="8" align="center">
          <n-tag size="small" :type="routeType" :bordered="false" round>route {{ result.route }}</n-tag>
          <n-tag size="small" :bordered="false" round>{{ result.elapsedMs }} ms</n-tag>
          <n-tag size="small" type="success" :bordered="false" round>{{ result.count }} 条</n-tag>
        </n-space>
      </template>
      <n-data-table
        :columns="columns"
        :data="hits"
        :bordered="false"
        size="small"
        :row-key="(row: RecallHit) => String(row.id || row.narrative)"
      />
      <div v-if="result.rerank === 'applied'" class="faint" style="margin-top: 8px">
        已精排：只列精排窗口内过阈值的条目，所以可能少于 topK。
      </div>
    </n-card>

    <n-card size="small" title="注入给模型的原文">
      <pre class="pre-block">{{ result.injection || '（空）' }}</pre>
    </n-card>
  </template>
</template>
