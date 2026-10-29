import type { ThemeConfig } from 'antd'

/**
 * Chart palette, shared by every chart so "green means settled successfully" means the
 * same thing on the throughput line and on the latency bars. The keys also match the
 * backend's vocabulary, which makes a mismatch obvious when reading both sides.
 */
export const CHART_COLORS = {
  events: '#1668dc',
  attempts: '#8c8c8c',
  succeeded: '#52c41a',
  dead: '#ff4d4f',
  canceled: '#faad14',
} as const

/** A small, deliberate theme: AntD defaults are fine, the accent is not. */
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1668dc',
    borderRadius: 6,
    fontSize: 13,
  },
  components: {
    Layout: {
      // The console is a dense operator tool, not a marketing page.
      headerBg: '#ffffff',
      siderBg: '#001529',
    },
  },
}
