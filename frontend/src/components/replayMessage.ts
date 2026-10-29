import type { BatchReplay } from '@/api/types'

/**
 * One sentence for the operator, because the batch endpoint's answer is a partial result and
 * a plain "已重放" would hide the id that moved on under another tab.
 */
export function describeBatchReplay(result: BatchReplay): string {
  if (result.rejected === 0) {
    return `已重放 ${result.replayed} 条投递`
  }
  const firstRejection = result.results.find((item) => !item.replayed)
  // The backend always fills one of the two for a rejected id; the fallback is for the
  // case a future version stops doing that, not for a state the UI expects.
  const reason = firstRejection?.message ?? '状态已变化'
  return `已重放 ${result.replayed} 条，${result.rejected} 条被拒绝：${reason}`
}
