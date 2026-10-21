<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NModal,
  NProgress,
  NSpace,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../lib/api'
import { toast, toastError } from '../lib/feedback'
import { fmtTime, num } from '../lib/format'
import { refreshTick } from '../lib/store'
import type {
  LearningOverview,
  ProcSkill,
  ProcStep,
  ProcTrajectory,
  ProcTrajectoryDetail,
  ProcWorld,
} from '../types'

const data = ref<LearningOverview | null>(null)
const loading = ref(false)
const busy = ref(false)

const detail = ref<ProcTrajectoryDetail | null>(null)
const detailOpen = ref(false)
const detailLoading = ref(false)

const skillBody = ref('')
const skillTitle = ref('')
const skillOpen = ref(false)
const skillLoading = ref(false)

const counts = computed(() => ({
  runs: data.value?.trajectories.length ?? 0,
  strategies: data.value?.strategies.length ?? 0,
  skills: data.value?.skills.length ?? 0,
  world: data.value?.world.length ?? 0,
}))

const closed = computed(() => (data.value?.trajectories ?? []).filter((r) => r.status !== 'open'))

const trajectoryColumns: DataTableColumns<ProcTrajectory> = [
  { title: '开始', key: 'startedAt', width: 156, render: (r) => fmtTime(r.startedAt) },
  { title: '目标', key: 'goal', ellipsis: { tooltip: true } },
  {
    title: '结果',
    key: 'status',
    width: 92,
    render: (r) =>
      h(NTag, { size: 'small', bordered: false, type: statusType(r.status) }, { default: () => statusLabel(r.status) }),
  },
  { title: '步数', key: 'stepCount', width: 70, align: 'right', render: (r) => num(r.stepCount) },
  {
    title: '回报',
    key: 'reward',
    width: 80,
    align: 'right',
    render: (r) => (r.reward === null || r.reward === undefined ? '—' : Number(r.reward).toFixed(2)),
  },
  {
    title: '',
    key: 'open',
    width: 70,
    render: (row: ProcTrajectory) =>
      h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          onClick: () => void openRun(row),
        },
        { default: () => '步骤' },
      ),
  },
]

const worldColumns: DataTableColumns<ProcWorld> = [
  { title: '对象', key: 'subject', width: 110, ellipsis: { tooltip: true } },
  { title: '动作', key: 'action', ellipsis: { tooltip: true } },
  { title: '预期观察', key: 'expectation', ellipsis: { tooltip: true } },
  {
    title: '置信度',
    key: 'confidence',
    width: 120,
    render: (r) =>
      h(NProgress, {
        type: 'line',
        percentage: Math.round(r.confidence * 100),
        height: 8,
        showIndicator: false,
        color: r.confidence >= 0.7 ? '#18a058' : r.confidence >= 0.4 ? '#f0a020' : '#d03050',
      }),
  },
  { title: '印证', key: 'support', width: 64, align: 'right', render: (r) => num(r.support) },
  { title: '反例', key: 'contradictions', width: 64, align: 'right', render: (r) => num(r.contradictions) },
]

function statusLabel(s: string): string {
  const map: Record<string, string> = {
    open: '进行中',
    succeeded: '成功',
    failed: '失败',
    abandoned: '放弃',
  }
  return map[s] || s
}

function statusType(s: string): 'default' | 'success' | 'error' | 'warning' {
  if (s === 'succeeded') return 'success'
  if (s === 'failed') return 'error'
  if (s === 'abandoned') return 'warning'
  return 'default'
}

function strategyStatus(s: string): { label: string; type: 'default' | 'success' | 'warning' } {
  if (s === 'active') return { label: '可用', type: 'success' }
  if (s === 'quarantined') return { label: '已隔离', type: 'warning' }
  return { label: '候选', type: 'default' }
}

function creditColor(v: number): string {
  if (v >= 0.5) return '#18a058'
  if (v > 0) return '#36ad6a'
  if (v <= -0.5) return '#d03050'
  return '#f0a020'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await api<LearningOverview>('/learning')
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function distill(): Promise<void> {
  busy.value = true
  try {
    const r = await api<{ strategies: number; skills: number; quarantined: number }>('/learning/distill', {
      method: 'POST',
    })
    toast(`学习完成：${r.strategies} 份策略、${r.skills} 份技能${r.quarantined ? `，${r.quarantined} 份被隔离` : ''}`)
    await load()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function openRun(row: ProcTrajectory): Promise<void> {
  detailLoading.value = true
  detailOpen.value = true
  detail.value = null
  try {
    detail.value = await api<ProcTrajectoryDetail>(`/learning/trajectories/${row.id}`)
  } catch (e) {
    toastError((e as Error).message)
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function openSkill(row: ProcSkill): Promise<void> {
  skillLoading.value = true
  skillOpen.value = true
  skillBody.value = ''
  skillTitle.value = row.title
  try {
    // 技能正文是 Markdown，不走 JSON 封装；这里直接取原始文本
    const res = await fetch(`/admin/api/learning/skills/${row.name}`, { credentials: 'same-origin' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    skillBody.value = await res.text()
  } catch (e) {
    toastError((e as Error).message)
  } finally {
    skillLoading.value = false
  }
}

function stepCredit(step: ProcStep): number | null {
  return step.credit === null || step.credit === undefined ? null : Number(step.credit)
}

onMounted(() => void load())
watch(refreshTick, () => void load())
</script>

<template>
  <n-alert v-if="data && !data.enabled" type="warning" :bordered="false" style="margin-bottom: 16px">
    程序性记忆已在服务端关闭（MEMORY_PROC_ENABLED=false）。轨迹仍会保留，但不会再蒸馏出策略与技能。
  </n-alert>

  <div class="cards">
    <div class="stat">
      <div class="k">轨迹</div>
      <div class="v">{{ num(counts.runs) }}</div>
      <div class="sub">已结束 {{ num(closed.length) }} 条</div>
    </div>
    <div class="stat">
      <div class="k">策略</div>
      <div class="v">{{ num(counts.strategies) }}</div>
      <div class="sub">同类任务重复 {{ data?.thresholds.minSupport ?? '—' }} 次起</div>
    </div>
    <div class="stat">
      <div class="k">技能</div>
      <div class="v">{{ num(counts.skills) }}</div>
      <div class="sub">支撑 ≥ {{ data?.thresholds.crystallizeSupport ?? '—' }} 且成功率达标</div>
    </div>
    <div class="stat">
      <div class="k">世界模型</div>
      <div class="v">{{ num(counts.world) }}</div>
      <div class="sub">动作 → 预期观察</div>
    </div>
  </div>

  <n-card size="small" style="margin-bottom: 16px">
    <n-space align="center" justify="space-between">
      <n-space align="center" size="small">
        <n-tag size="small" :bordered="false">折扣 γ {{ data?.thresholds.gamma ?? '—' }}</n-tag>
        <n-tag size="small" :bordered="false">反思加权 λ {{ data?.thresholds.reflectionBonus ?? '—' }}</n-tag>
        <n-tag size="small" :bordered="false">
          结晶门槛 {{ data?.thresholds.crystallizeSupport ?? '—' }} 次 /
          {{ Math.round((data?.thresholds.crystallizeSuccess ?? 0) * 100) }}%
        </n-tag>
      </n-space>
      <n-button size="small" type="primary" :loading="busy" :disabled="!data?.enabled" @click="distill">
        立即学习
      </n-button>
    </n-space>
    <div class="help">
      关掉轨迹时把终局奖励沿步骤倒着回传，带具体反思的步骤权重更高；同类任务攒够了才升成策略，
      策略再攒够才结晶成技能。技能只从成功样本生成，观察里出现指令式内容会整份隔离。
    </div>
  </n-card>

  <n-tabs type="line" animated>
    <n-tab-pane name="runs" :tab="`轨迹 ${counts.runs}`">
      <n-card size="small">
        <n-data-table
          v-if="data?.trajectories.length"
          :columns="trajectoryColumns"
          :data="data.trajectories"
          :bordered="false"
          size="small"
          :loading="loading"
          :row-key="(row: ProcTrajectory) => row.id"
        />
        <n-empty v-else description="还没有轨迹。插件或调用方 POST /v1/memory/trajectories 开始记录。" />
      </n-card>
    </n-tab-pane>

    <n-tab-pane name="strategies" :tab="`策略 ${counts.strategies}`">
      <n-empty v-if="!data?.strategies.length" description="同类任务还没有重复成功到可以总结。" />
      <n-card v-for="s in data?.strategies ?? []" :key="s.id" size="small" style="margin-bottom: 12px">
        <template #header>
          <n-space align="center" size="small">
            <n-tag size="small" :bordered="false" :type="strategyStatus(s.status).type">
              {{ strategyStatus(s.status).label }}
            </n-tag>
            <span>{{ s.goal }}</span>
          </n-space>
        </template>
        <template #header-extra>
          <n-space align="center" size="small">
            <n-tag size="small" :bordered="false">支撑 {{ s.support }}</n-tag>
            <n-tag size="small" :bordered="false">成功率 {{ Math.round(s.successRate * 100) }}%</n-tag>
            <n-tag size="small" :bordered="false">平均回报 {{ s.meanReturn.toFixed(2) }}</n-tag>
          </n-space>
        </template>

        <div class="muted" style="margin-bottom: 8px">{{ s.whenToUse }}</div>
        <div v-for="step in s.steps" :key="step.order" class="proc-step">
          <div class="line">
            <span class="ord">{{ step.order }}</span>
            <span>{{ step.action }}</span>
          </div>
          <div v-if="step.why" class="why">为什么：{{ step.why }}</div>
          <div v-if="step.expect" class="why">预期：{{ step.expect }}</div>
        </div>
        <div v-if="s.pitfalls?.length" class="pitfalls">
          <div class="pitfall-title">常见坑</div>
          <div v-for="(p, i) in s.pitfalls" :key="i">· {{ p }}</div>
        </div>
      </n-card>
    </n-tab-pane>

    <n-tab-pane name="skills" :tab="`技能 ${counts.skills}`">
      <n-empty v-if="!data?.skills.length" description="还没有技能。策略的支撑次数与成功率都达标后会自动结晶。" />
      <n-space vertical size="small">
        <n-card v-for="k in data?.skills ?? []" :key="k.name" size="small" hoverable @click="openSkill(k)">
          <n-space align="center" size="small">
            <n-tag size="small" :bordered="false" type="success">v{{ k.version }}</n-tag>
            <strong>{{ k.title }}</strong>
            <span class="mono muted">{{ k.name }}</span>
          </n-space>
          <div class="muted" style="margin-top: 6px">{{ k.description }}</div>
          <div class="help">更新于 {{ fmtTime(k.updatedAt) }} · 点开看 SKILL.md 全文</div>
        </n-card>
      </n-space>
    </n-tab-pane>

    <n-tab-pane name="world" :tab="`世界模型 ${counts.world}`">
      <n-card size="small">
        <n-data-table
          v-if="data?.world.length"
          :columns="worldColumns"
          :data="data.world"
          :bordered="false"
          size="small"
          :loading="loading"
          :row-key="(row: ProcWorld) => `${row.subject}-${row.action}-${row.expectation}`"
        />
        <n-empty v-else description="成功轨迹里的「动作 → 观察」会出现在这里。" />
        <div class="help">
          置信度用的是平滑后的印证比例；同一动作在失败轨迹里留下不同观察时会记一次反例并降权，不删规则。
        </div>
      </n-card>
    </n-tab-pane>
  </n-tabs>

  <n-modal v-model:show="detailOpen" preset="card" style="width: 760px" title="轨迹详情">
    <div v-if="detailLoading" class="muted">加载中…</div>
    <template v-else-if="detail">
      <div class="muted" style="margin-bottom: 10px">
        {{ detail.trajectory.goal }} ·
        {{ statusLabel(detail.trajectory.status) }} ·
        回报 {{ detail.trajectory.reward ?? '—' }}
      </div>
      <div v-for="step in detail.steps" :key="step.idx" class="proc-step">
        <div class="line">
          <span class="ord">{{ step.idx + 1 }}</span>
          <span>{{ step.action }}</span>
          <span class="grow"></span>
          <n-tag
            v-if="stepCredit(step) !== null"
            size="tiny"
            :bordered="false"
            :color="{ color: creditColor(stepCredit(step) as number) + '22', textColor: creditColor(stepCredit(step) as number) }"
          >
            信用 {{ (stepCredit(step) as number).toFixed(2) }}
          </n-tag>
        </div>
        <div v-if="step.observation" class="why">观察：{{ step.observation }}</div>
        <div v-if="step.reflection" class="why">反思：{{ step.reflection }}</div>
      </div>
      <div v-if="detail.trajectory.reflection" class="pitfalls">
        <div class="pitfall-title">整条轨迹的反思</div>
        <div>{{ detail.trajectory.reflection }}</div>
      </div>
    </template>
    <n-empty v-else description="没有数据" />
  </n-modal>

  <n-modal v-model:show="skillOpen" preset="card" style="width: 820px" :title="skillTitle">
    <div v-if="skillLoading" class="muted">加载中…</div>
    <pre v-else class="skill-body">{{ skillBody }}</pre>
  </n-modal>
</template>

<style scoped>
.proc-step {
  border-left: 2px solid var(--n-border-color, #e5e7eb);
  padding: 4px 0 4px 12px;
  margin-bottom: 8px;
}

.proc-step .line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.proc-step .ord {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  border-radius: 10px;
  background: rgba(128, 128, 128, 0.16);
  font-size: 12px;
}

.proc-step .why {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.72;
}

.pitfalls {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(240, 160, 32, 0.1);
  font-size: 13px;
}

.pitfall-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.skill-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12.5px;
  line-height: 1.6;
  max-height: 56vh;
  overflow: auto;
  margin: 0;
}

.grow {
  flex: 1;
}
</style>
