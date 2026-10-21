<script setup lang="ts">
import { computed } from 'vue'
import {
  NConfigProvider,
  NDialogProvider,
  NLoadingBarProvider,
  NMessageProvider,
  darkTheme,
  dateZhCN,
  useOsTheme,
  zhCN,
} from 'naive-ui'
import { themeOverrides } from './lib/theme'
import AppRoot from './components/AppRoot.vue'

const osTheme = useOsTheme()
const theme = computed(() => (osTheme.value === 'dark' ? darkTheme : null))
</script>

<template>
  <n-config-provider
    :theme="theme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-message-provider :max="3">
      <n-dialog-provider>
        <n-loading-bar-provider>
          <app-root />
        </n-loading-bar-provider>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
