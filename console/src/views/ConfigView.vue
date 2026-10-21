<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NForm,
  NGrid,
  NGridItem,
  NProgress,
  NSpace,
  NTag,
} from 'naive-ui'
import ConfigInput from '../components/ConfigInput.vue'
import { ApiError, api } from '../lib/api'
import { confirmAction, toast, toastError } from '../lib/feedback'
import { startReindex, reindex, syncReindex } from '../lib/reindex'
import { loadOverview, refreshTick } from '../lib/store'
import type { ConfigField, ConfigResponse, EmbeddingProbe, RerankProbe } from '../types'

const data = ref<ConfigResponse | null>(null)
const draft = reactive<Record<string, string | number | null>>({})
const resets = reactive(new Set<string>())
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testingRerank = ref(false)
const starting = ref(false)
const probe = ref<EmbeddingProbe | null>(null)
const rerankProbe = ref<RerankProbe | null>(null)

const fields = computed<ConfigField[]>(() => data.value?.fields || [])
const embeddingFields = computed(() => fields.value.filter((f) => f.group === 'embedding'))
const searchFields = computed(() => fields.value.filter((f) => f.group === 'search'))
const rerankFields = computed(() => fields.value.filter((f) => f.group === 'rerank'))
const overridden = computed(() => new Set(data.value?.overridden || []))
const requiresReindex = computed(() => Boolean(data.value?.space?.requiresReindex))

const bannerText = computed(() => {
  const space = data.value?.space
  if (!space) return ''
  return `当前索引用的是 ${space.spaceId || '—'}。换过模型或维度后需要重建，否则语义召回不可用。`
})

function isDirty(field: ConfigField): boolean {
  if (resets.has(field.key)) return true
  const value = draft[field.key]
  if (field.type === 'SECRET') return String(value ?? '') !== ''
  return String(value ?? '') !== String(field.value ?? '')
}

const dirtyKeys = computed(() => fields.value.filter(isDirty).map((f) => f.key))
const canSave = computed(() => dirtyKeys.value.length > 0 && !saving.value)

function apply(response: ConfigResponse): void {
  data.value = response
  resets.clear()
  for (const key of Object.keys(draft)) delete draft[key]
  for (const field of response.fields) {
    draft[field.key] = field.type === 'SECRET' ? '' : (field.value ?? '')
  }
  syncReindex(response.reindex)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    apply(await api<ConfigResponse>('/config'))
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

function resetField(field: ConfigField): void {
  resets.add(field.key)
  draft[field.key] = field.type === 'SECRET' ? '' : ((field.fallback as string | number | null) ?? '')
}

async function save(): Promise<void> {
  if (!canSave.value) return
  const values: Record<string, unknown> = {}
  for (const key of dirtyKeys.value) {
    const field = fields.value.find((f) => f.key === key)
    if (!field) continue
    if (resets.has(key)) {
      values[key] = null
      continue
    }
    const raw = draft[key]
    if (field.type === 'INT' || field.type === 'DOUBLE') {
      if (raw === '' || raw === null || raw === undefined) continue
      values[key] = Number(raw)
    } else {
      values[key] = raw
    }
  }

  saving.value = true
  try {
    const response = await api<ConfigResponse>('/config', {
      method: 'PUT',
      body: JSON.stringify({ values, revision: data.value?.revision }),
    })
    apply(response)
    toast(response.result?.note ? `已保存：${response.result.note}` : '配置已保存并生效')
    await loadOverview().catch(() => null)
  } catch (e) {
    const err = e as ApiError
    if (err.status === 409) {
      toastError('配置已被别处修改，已为你刷新')
      await load()
    } else {
      toastError(err.message)
    }
  } finally {
    saving.value = false
  }
}

function numberOrUndefined(key: string): number | undefined {
  const value = draft[key]
  if (value === '' || value === null || value === undefined) return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

async function testEmbedding(): Promise<void> {
  testing.value = true
  probe.value = null
  try {
    probe.value = await api<EmbeddingProbe>('/config/test-embedding', {
      method: 'POST',
      body: JSON.stringify({
        provider: draft['embedding.provider'],
        baseUrl: draft['embedding.base-url'],
        apiKey: draft['embedding.api-key'],
        model: draft['embedding.model'],
        dims: numberOrUndefined('embedding.dims'),
        timeoutMs: numberOrUndefined('embedding.timeout-ms'),
      }),
    })
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    testing.value = false
  }
}

async function testRerank(): Promise<void> {
  testingRerank.value = true
  rerankProbe.value = null
  try {
    rerankProbe.value = await api<RerankProbe>('/config/test-rerank', {
      method: 'POST',
      body: JSON.stringify({
        provider: draft['rerank.provider'],
        baseUrl: draft['rerank.base-url'],
        apiKey: draft['rerank.api-key'],
        model: draft['rerank.model'],
        topN: numberOrUndefined('rerank.top-n'),
        timeoutMs: numberOrUndefined('rerank.timeout-ms'),
      }),
    })
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    testingRerank.value = false
  }
}

async function reindexNow(): Promise<void> {  const ok = await confirmAction(
    '重建期间语义召回暂停，关键词召回不受影响。继续？',
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

const reindexState = computed(() => reindex.state)
const reindexPercent = computed(() => {
  const s = reindex.state
  if (!s || !s.total) return s?.status === 'done' ? 100 : 0
  return Math.min(100, Math.round((s.processed * 1000) / s.total) / 10)
})

onMounted(load)
watch(refreshTick, load)
watch(
  () => reindex.finishedTick,
  () => {
    void load()
  },
)
</script>

<template>
  <n-alert v-if="requiresReindex" type="warning" :bordered="false" style="margin-bottom: 16px">
    <div class="row">
      <div class="grow">
        <strong>索引需要重建</strong>
        <div class="help">{{ bannerText }}</div>
      </div>
      <n-button size="small" type="primary" :loading="starting" @click="reindexNow">
        重建向量索引
      </n-button>
    </div>
  </n-alert>

  <n-card size="small" title="嵌入模型" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button size="tiny" :loading="testing" @click="testEmbedding">测试连接</n-button>
    </template>

    <n-form label-placement="top" :show-feedback="true">
      <n-grid cols="1 760:2" :x-gap="20">
        <n-grid-item v-for="field in embeddingFields" :key="field.key">
          <config-input
            :field="field"
            :value="draft[field.key] ?? null"
            :covered="overridden.has(field.key) || resets.has(field.key)"
            @update:value="(v) => (draft[field.key] = v)"
            @reset="resetField(field)"
          />
        </n-grid-item>
      </n-grid>
    </n-form>

    <n-alert
      v-if="probe"
      :type="probe.ok ? 'success' : 'error'"
      :bordered="false"
      style="margin-top: 4px"
      :title="probe.ok ? '连接成功' : '连接失败'"
    >
      <template v-if="probe.ok">
        {{ probe.provider }} / {{ probe.model }} · 返回 {{ probe.actualDims }} 维 ·
        {{ probe.latencyMs }} ms · 样例 [{{ (probe.sample || []).join(', ') }}]
        <div v-if="probe.warning" class="help">{{ probe.warning }}</div>
      </template>
      <template v-else>
        {{ probe.error || '未知错误' }} · {{ probe.latencyMs }} ms
      </template>
    </n-alert>
  </n-card>

  <n-card size="small" title="检索参数" style="margin-bottom: 16px">
    <n-form label-placement="top">
      <n-grid cols="1 760:2" :x-gap="20">
        <n-grid-item v-for="field in searchFields" :key="field.key">
          <config-input
            :field="field"
            :value="draft[field.key] ?? null"
            :covered="overridden.has(field.key) || resets.has(field.key)"
            @update:value="(v) => (draft[field.key] = v)"
            @reset="resetField(field)"
          />
        </n-grid-item>
      </n-grid>
    </n-form>
  </n-card>

  <n-card size="small" title="精排" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button size="tiny" :loading="testingRerank" @click="testRerank">测试区分度</n-button>
    </template>

    <n-form label-placement="top">
      <n-grid cols="1 760:2" :x-gap="20">
        <n-grid-item v-for="field in rerankFields" :key="field.key">
          <config-input
            :field="field"
            :value="draft[field.key] ?? null"
            :covered="overridden.has(field.key) || resets.has(field.key)"
            @update:value="(v) => (draft[field.key] = v)"
            @reset="resetField(field)"
          />
        </n-grid-item>
      </n-grid>
    </n-form>

    <n-alert
      v-if="rerankProbe"
      :type="rerankProbe.ok ? (rerankProbe.warning ? 'warning' : 'success') : 'error'"
      :bordered="false"
      :title="rerankProbe.skipped ? '未启用精排' : rerankProbe.ok ? '精排可用' : '精排调用失败'"
    >
      <template v-if="rerankProbe.skipped">{{ rerankProbe.note }}</template>
      <template v-else-if="rerankProbe.ok">
        {{ rerankProbe.model }} · {{ rerankProbe.latencyMs }} ms · 相关/不相关 = [{{ (rerankProbe.scores || []).join(', ') }}]
        <span v-if="rerankProbe.spread !== undefined"> · 分差 {{ rerankProbe.spread }}</span>
        <div v-if="rerankProbe.warning" class="help">{{ rerankProbe.warning }}</div>
      </template>
      <template v-else>
        {{ rerankProbe.error || '未知错误' }} · {{ rerankProbe.latencyMs }} ms
      </template>
    </n-alert>
    <div v-else class="help" style="margin-top: 4px">
      未配置时按关键词与向量的名次直接排序。
    </div>
  </n-card>

  <n-card v-if="reindexState && reindexState.status && reindexState.status !== 'idle'" size="small" style="margin-bottom: 16px">
    <n-space align="center" style="margin-bottom: 10px">
      <n-tag
        size="small"
        :type="reindexState.status === 'running' ? 'info' : reindexState.status === 'done' ? 'success' : 'error'"
        :bordered="false"
      >
        {{ reindexState.status }}
      </n-tag>
      <span class="muted">{{ reindexState.processed }} / {{ reindexState.total }} 条</span>
      <span class="grow"></span>
      <span class="mono muted">{{ reindexPercent }}%</span>
    </n-space>
    <n-progress type="line" :percentage="reindexPercent" :show-indicator="false" :height="8" />
    <div class="help">
      成功 {{ reindexState.embedded }} · 失败 {{ reindexState.failed }}
      <span v-if="reindexState.message">· {{ reindexState.message }}</span>
    </div>
  </n-card>

  <n-card size="small">
    <n-space align="center">
      <span class="muted">
        {{ dirtyKeys.length === 0 ? '改动点保存后生效。' : `有 ${dirtyKeys.length} 项未保存。` }}
      </span>
      <span class="grow"></span>
      <n-button :disabled="saving || loading" @click="load">放弃修改</n-button>
      <n-button type="primary" :disabled="!canSave" :loading="saving" @click="save">保存配置</n-button>
    </n-space>
  </n-card>
</template>
