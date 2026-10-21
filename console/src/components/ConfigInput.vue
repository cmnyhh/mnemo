<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NFormItem, NInput, NInputNumber, NSelect, NTag } from 'naive-ui'
import type { ConfigField } from '../types'

/**
 * 一个配置项的控件。类型、选项、范围全部来自服务端下发的元数据，
 * 这里不认识任何具体的键名——加一个配置项不需要动这个组件。
 */
const props = defineProps<{
  field: ConfigField
  value: string | number | null
  covered: boolean
}>()

const emit = defineEmits<{
  (e: 'update:value', value: string | number | null): void
  (e: 'reset'): void
}>()

const baseline = computed(() => {
  const fallback = props.field.fallback
  return fallback === null || fallback === undefined || fallback === '' ? '（空）' : String(fallback)
})

const selectOptions = computed(() => (props.field.options || []).map((o) => ({ label: o, value: o })))

const secretPlaceholder = computed(() =>
  props.field.masked ? `已设置 ${props.field.masked}，留空表示不改` : '未设置',
)
</script>

<template>
  <n-form-item :show-feedback="true">
    <template #label>
      <span>{{ field.label }}</span>
      <n-tag v-if="covered" size="tiny" type="info" :bordered="false" style="margin-left: 6px">
        已覆盖
      </n-tag>
      <n-button
        v-if="covered"
        size="tiny"
        text
        type="primary"
        style="margin-left: 6px"
        @click="emit('reset')"
      >
        恢复
      </n-button>
    </template>

    <n-select
      v-if="field.type === 'ENUM'"
      :value="(value as string) ?? null"
      :options="selectOptions"
      size="small"
      @update:value="(v: string) => emit('update:value', v)"
    />

    <n-input-number
      v-else-if="field.type === 'INT' || field.type === 'DOUBLE'"
      :value="typeof value === 'number' ? value : null"
      size="small"
      style="width: 100%"
      :step="field.type === 'INT' ? 1 : 0.01"
      :precision="field.type === 'INT' ? 0 : 2"
      :min="field.min ?? undefined"
      :max="field.max ?? undefined"
      :show-button="false"
      @update:value="(v: number | null) => emit('update:value', v)"
    />

    <n-input
      v-else-if="field.type === 'SECRET'"
      :value="(value as string) ?? ''"
      size="small"
      type="password"
      show-password-on="click"
      :placeholder="secretPlaceholder"
      :input-props="{ autocomplete: 'new-password' }"
      @update:value="(v: string) => emit('update:value', v)"
    />

    <n-input
      v-else
      :value="(value as string) ?? ''"
      size="small"
      :placeholder="field.placeholder || ''"
      @update:value="(v: string) => emit('update:value', v)"
    />

    <template #feedback>
      <span class="help">
        {{ field.help }}
        <span class="faint">· 基线 {{ baseline }}</span>
      </span>
    </template>
  </n-form-item>
</template>
