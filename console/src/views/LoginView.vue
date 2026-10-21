<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput } from 'naive-ui'
import { ApiError, api, setCsrf } from '../lib/api'
import { session } from '../lib/store'

const props = defineProps<{ hint?: string }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const username = ref('')
const password = ref('')
const error = ref(props.hint || '')
const busy = ref(false)
let countdown: number | null = null

watch(
  () => props.hint,
  (value) => {
    if (value) error.value = value
  },
)

function stopCountdown(): void {
  if (countdown !== null) {
    window.clearInterval(countdown)
    countdown = null
  }
}

async function submit(): Promise<void> {
  stopCountdown()
  if (!username.value.trim()) {
    error.value = '请输入账号'
    return
  }
  if (!password.value) {
    error.value = '请输入口令'
    return
  }

  busy.value = true
  error.value = ''
  try {
    const res = await api<{ csrfToken?: string; username?: string }>(
      '/login',
      { method: 'POST', body: JSON.stringify({ username: username.value.trim(), password: password.value }) },
      // 口令错也是 401，这条路径不能触发「会话失效」逻辑
      { silent401: true },
    )
    setCsrf(res.csrfToken)
    session.username = res.username || username.value.trim()
    password.value = ''
    emit('success')
  } catch (e) {
    const err = e as ApiError
    if (err.status === 429) {
      const retry = Number((err.data?.retryAfterSec as number) || 60)
      let left = retry
      error.value = `尝试次数过多，${left} 秒后再试`
      countdown = window.setInterval(() => {
        left -= 1
        if (left <= 0) {
          stopCountdown()
          error.value = ''
          return
        }
        error.value = `尝试次数过多，${left} 秒后再试`
      }, 1000)
    } else {
      error.value = err.message === 'unauthorized' ? '账号或口令不正确' : err.message || '登录失败'
    }
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="gate">
    <div class="gate-card">
      <div class="gate-brand">
        <div class="mark">M</div>
        <div>
          <h1>mnemo 控制台</h1>
          <p>长期记忆服务</p>
        </div>
      </div>

      <n-alert v-if="error" type="error" :bordered="false" style="margin-bottom: 14px">
        {{ error }}
      </n-alert>

      <n-form label-placement="top" @submit.prevent="submit">
        <n-form-item label="账号">
          <n-input
            v-model:value="username"
            placeholder="输入账号"
            autofocus
            :input-props="{ autocomplete: 'username' }"
          />
        </n-form-item>
        <n-form-item label="口令">
          <n-input
            v-model:value="password"
            type="password"
            show-password-on="click"
            placeholder="输入口令"
            :input-props="{ autocomplete: 'current-password' }"
            @keyup.enter="submit"
          />
        </n-form-item>
        <n-button type="primary" block attr-type="submit" :loading="busy">登录</n-button>
      </n-form>

      <div class="gate-note">
        账号与口令只保存在你的服务器上。会话 {{ session.sessionHours }} 小时后失效。
      </div>
    </div>
  </div>
</template>
