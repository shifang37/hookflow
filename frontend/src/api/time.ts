/**
 * Formatting helpers.
 *
 * Every timestamp the backend returns is a *naive* local date-time that is really UTC
 * (`spring.jackson.time-zone: UTC`). `new Date('2026-10-29T08:31:02')` would be parsed
 * as local time, silently shifting every row by the viewer's offset — in Asia/Shanghai
 * that is eight hours, which is exactly the kind of bug that gets blamed on the server.
 */

const HAS_TIMEZONE = /(?:Z|[+-]\d{2}:?\d{2})$/

/** Treats an offset-less backend timestamp as UTC. */
export function parseTimestamp(value: string): Date {
  return new Date(HAS_TIMEZONE.test(value) ? value : `${value}Z`)
}

/** `2026-10-29 16:31:02` — local time, seconds precision. Never `Invalid Date`. */
export function formatTimestamp(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const date = parseTimestamp(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** Chart axis label: hour:minute, which is all a 24h chart needs. */
export function formatBucketLabel(value: string): string {
  const date = parseTimestamp(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** `—` instead of `0 ms` when nothing was measured. */
export function formatMillis(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value} ms`
}

export function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value.toFixed(2)}%`
}

export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : value.toLocaleString('en-US')
}

/** Bucket label for a latency histogram, e.g. `≤ 50ms`, `100–250ms`, `≥ 10s`. */
export function formatLatencyBucket(upperBoundMs: number | null, previousBoundMs: number | null): string {
  if (upperBoundMs === null) {
    return previousBoundMs === null ? '≥ ?' : `> ${formatBound(previousBoundMs)}`
  }
  return previousBoundMs === null ? `≤ ${formatBound(upperBoundMs)}` : `${formatBound(previousBoundMs)}–${formatBound(upperBoundMs)}`
}

function formatBound(ms: number): string {
  return ms >= 1000 ? `${ms / 1000}s` : `${ms}ms`
}
