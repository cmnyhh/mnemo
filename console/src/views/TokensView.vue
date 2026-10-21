<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSpace,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { confirmAction, toast, toastError } from '../lib/feedback'
import { fmtTime } from '../lib/format'
import type { ApiTokenList, ApiTokenRow, CreatedToken } from '../types'

const items = ref<ApiTokenRow[]>([])
const tenant = ref('')
const loading = ref(false)
const busyId = ref('')

const createOpen = ref(false)
const creating = ref(false)
const name = ref('')
// 默认 90 天：永不过期的令牌一旦泄露，只能靠"记得去撤销"这一层人肉防线。
// 留空仍然可以签发永久令牌，但要主动清掉这个数字。
const expiresInDays = ref<number | null>(90)

const issued = ref<CreatedToken | null>(null)
const issuedOpen = ref(false)
const copied = ref(false)

const stateMeta: Record<string, { label: string; type: 'success' | 'warning' | 'default' | 'error' }> = {
  active: { label: '有效', type: 'success' },
  expired: { label: '已过期', type: 'warning' },
  revoked: { label: '已撤销', type: 'error' },
}

const columns = computed<DataTableColumns<ApiTokenRow>>(() => [
  {
    title: '名称',
    key: 'name',
    width: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: '令牌',
    key: 'prefix',
    width: 156,
    render: (row) => h('span', { class: 'mono' }, `${row.prefix}…`),
  },
  {
    title: '状态',
    key: 'state',
    width: 92,
    render: (row) => {
      const meta = stateMeta[row.state] ?? stateMeta.active
      return h(NTag, { size: 'small', type: meta.type, bordered: false }, { default: () => meta.label })
    },
  },
  {
    title: '创建',
    key: 'createdAt',
    width: 160,
    render: (row) => h('span', { class: 'mono faint' }, fmtTime(row.createdAt)),
  },
  {
    title: '最后使用',
    key: 'lastUsedAt',
    width: 160,
    render: (row) =>
      row.lastUsedAt
        ? h('span', { class: 'mono faint' }, fmtTime(row.lastUsedAt))
        : h('span', { class: 'faint' }, '未使用'),
  },
  {
    title: '过期',
    key: 'expiresAt',
    width: 160,
    render: (row) =>
      row.expiresAt
        ? h('span', { class: 'mono faint' }, fmtTime(row.expiresAt))
        : h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => '永不过期' }),
  },
  {
    title: '操作',
    key: 'ops',
    width: 132,
    render: (row) => {
      if (row.state === 'active') {
        return h(
          NButton,
          {
            size: 'tiny',
            tertiary: true,
            type: 'error',
            loading: busyId.value === row.id,
            onClick: () => revoke(row),
          },
          { default: () => '撤销' },
        )
      }
      return h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          loading: busyId.value === row.id,
          onClick: () => remove(row),
        },
        { default: () => '删除' },
      )
    },
  },
])

async function load(): Promise<void> {
  loading.value = true
  try {
    const d = await api<ApiTokenList>('/tokens')
    items.value = d.items || []
    tenant.value = d.tenant || ''
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  name.value = ''
  expiresInDays.value = 90
  createOpen.value = true
}

async function create(): Promise<void> {
  if (!name.value.trim()) {
    toastError('给令牌起个名字')
    return
  }
  creating.value = true
  try {
    const d = await api<CreatedToken>('/tokens', {
      method: 'POST',
      body: JSON.stringify({ name: name.value.trim(), expiresInDays: expiresInDays.value }),
    })
    issued.value = d
    issuedOpen.value = true
    copied.value = false
    createOpen.value = false
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    creating.value = false
  }
}

async function revoke(row: ApiTokenRow): Promise<void> {
  const last = items.value.filter((r) => r.state === 'active').length <= 1
  const ok = await confirmAction(
    last
      ? `「${row.name}」是最后一枚有效令牌，撤销后需要重新签发才能访问。确定？`
      : `撤销「${row.name}」？使用它的客户端会立刻失去访问权限。`,
    '撤销令牌',
  )
  if (!ok) return
  busyId.value = row.id
  try {
    const r = await api<{ ok: boolean; note: string }>(`/tokens/${row.id}/revoke`, { method: 'POST' })
    if (r.ok) {
      toast(r.note)
    } else {
      toastError(r.note)
    }
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    busyId.value = ''
  }
}

async function remove(row: ApiTokenRow): Promise<void> {
  const ok = await confirmAction(`删除「${row.name}」？`, '删除')
  if (!ok) return
  busyId.value = row.id
  try {
    const r = await api<{ ok: boolean; note: string }>(`/tokens/${row.id}`, { method: 'DELETE' })
    if (r.ok) {
      toast(r.note)
    } else {
      toastError(r.note)
    }
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    busyId.value = ''
  }
}

/** 线上是 https，navigator.clipboard 可用；纯 http 打开时落回 execCommand。 */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限被拒就走下面的兜底
  }
  try {
    const el = document.createElement('textarea')
    el.value = text
    el.setAttribute('readonly', '')
    el.style.position = 'fixed'
    el.style.opacity = '0'
    document.body.appendChild(el)
    el.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(el)
    return ok
  } catch {
    return false
  }
}

async function copyIssued(): Promise<void> {
  if (!issued.value) return
  const ok = await copyText(issued.value.token)
  copied.value = ok
  ok ? toast('已复制到剪贴板') : toastError('复制失败，请手动选中后复制')
}

function closeIssued(): void {
  issuedOpen.value = false
  issued.value = null
}

onMounted(load)
</script>

<template>
  <n-card size="small" title="API 令牌" style="margin-bottom: 16px">
    <template #header-extra>
      <n-button type="primary" secondary size="small" @click="openCreate">创建令牌</n-button>
    </template>

    <n-data-table
      :columns="columns"
      :data="items"
      :loading="loading"
      :row-key="(row: ApiTokenRow) => row.id"
      :bordered="false"
      size="small"
    />

    <div class="help">
      明文只在创建时显示一次。撤销立即生效。
    </div>
  </n-card>

  <n-card size="small" title="怎么用">
    <div class="mono help" style="line-height: 1.9">
      curl -H "Authorization: Bearer &lt;令牌&gt;" \<br />
      &nbsp;&nbsp;https://&lt;你的域名&gt;/v1/memory/health
    </div>
    <div class="help">
      当前租户 <span class="mono">{{ tenant || '—' }}</span>，调用方不能自己指定。
      插件把令牌填在设置面板的 <span class="mono">serviceToken</span> 里。
    </div>
  </n-card>

  <n-modal
    v-model:show="createOpen"
    preset="card"
    title="创建令牌"
    style="width: 520px"
    :mask-closable="false"
  >
    <n-form label-placement="top">
      <n-form-item label="名称">
        <n-input v-model:value="name" placeholder="例如：笔记本、公司电脑" maxlength="64" show-count />
      </n-form-item>
      <n-form-item label="有效期（天）">
        <n-input-number
          v-model:value="expiresInDays"
          :min="1"
          :max="3650"
          placeholder="90"
          clearable
          style="width: 100%"
        />
      </n-form-item>
      <div class="help" style="margin: -8px 0 8px">
        留空表示永不过期，泄露后只能手动撤销。
      </div>
    </n-form>
    <n-space justify="end">
      <n-button quaternary @click="createOpen = false">取消</n-button>
      <n-button type="primary" :loading="creating" @click="create">创建</n-button>
    </n-space>
  </n-modal>

  <n-modal
    v-model:show="issuedOpen"
    preset="card"
    title="令牌已创建"
    style="width: 620px"
    :mask-closable="false"
    :closable="false"
  >
    <n-alert type="warning" :bordered="false" style="margin-bottom: 12px">
      只显示这一次，关掉后无法找回。
    </n-alert>
    <div v-if="issued" class="mono issued-token">{{ issued.token }}</div>
    <n-space justify="end" style="margin-top: 16px">
      <n-button @click="copyIssued">{{ copied ? '已复制' : '复制' }}</n-button>
      <n-button type="primary" @click="closeIssued">我已保存</n-button>
    </n-space>
  </n-modal>
</template>

<style scoped>
.issued-token {
  padding: 12px 14px;
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.12);
  word-break: break-all;
  line-height: 1.7;
  font-size: 13px;
}
</style>
