import type { GlobalThemeOverrides } from 'naive-ui'

/**
 * 只覆盖必要的几种：主色与圆角对齐原来的手写控制台，其余交给 Naive 默认值。
 * 全量重写一套 token 的维护成本，比它带来的视觉收益高得多。
 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#3b5bdb',
    primaryColorHover: '#4c6ae8',
    primaryColorPressed: '#2f4bc4',
    primaryColorSuppl: '#3b5bdb',
    infoColor: '#3b5bdb',
    successColor: '#1c7c47',
    warningColor: '#9a6500',
    errorColor: '#c0342b',
    borderRadius: '8px',
    borderRadiusSmall: '6px',
    fontSize: '13px',
    fontSizeSmall: '12px',
    fontWeightStrong: '500',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',
    fontFamilyMono: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
  },
  Card: {
    borderRadius: '12px',
  },
  DataTable: {
    thFontWeight: '500',
    thColor: '#f2f4f7',
    thTextColor: '#66707e',
    tdColorHover: '#f7f9fc',
    borderRadius: '12px',
  },
  Layout: {
    siderColor: '#ffffff',
    headerColor: '#ffffff',
  },
}
