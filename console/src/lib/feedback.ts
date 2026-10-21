import type { DialogApi, MessageApi } from 'naive-ui'

/**
 * 把 message / dialog 的实例挂到模块上，让 api 层和非组件代码也能提示。
 * 直接 createDiscreteApi 会更省事，但它拿不到 ConfigProvider 的主题与会话容器。
 */
let message: MessageApi | null = null
let dialog: DialogApi | null = null

export function bindFeedback(m: MessageApi, d: DialogApi): void {
  message = m
  dialog = d
}

export function toast(text: string): void {
  message?.success(text, { duration: 2600 })
}

export function toastError(text: string): void {
  message?.error(text || '操作失败', { duration: 3600 })
}

export function confirmAction(content: string, positiveText = '继续'): Promise<boolean> {
  return new Promise((resolve) => {
    if (!dialog) {
      resolve(window.confirm(content))
      return
    }
    dialog.warning({
      title: '请确认',
      content,
      positiveText,
      negativeText: '取消',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
}
