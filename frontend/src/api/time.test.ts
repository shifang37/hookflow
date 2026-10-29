import { describe, expect, it } from 'vitest'
import {
  formatBucketLabel,
  formatCount,
  formatLatencyBucket,
  formatMillis,
  formatPercent,
  formatTimestamp,
  parseTimestamp,
} from './time'

describe('parseTimestamp', () => {
  it('treats an offset-less backend timestamp as UTC', () => {
    // This is the whole reason the helper exists: `new Date('2026-10-29T08:31:02')`
    // would be parsed as local time and shift every row by the viewer's offset.
    expect(parseTimestamp('2026-10-29T08:31:02.114').toISOString()).toBe('2026-10-29T08:31:02.114Z')
  })

  it('leaves an explicit offset alone', () => {
    expect(parseTimestamp('2026-10-29T08:31:02+08:00').toISOString()).toBe('2026-10-29T00:31:02.000Z')
    expect(parseTimestamp('2026-10-29T08:31:02Z').toISOString()).toBe('2026-10-29T08:31:02.000Z')
  })
})

describe('formatTimestamp', () => {
  it('renders a dash for missing values instead of "Invalid Date"', () => {
    expect(formatTimestamp(null)).toBe('—')
    expect(formatTimestamp(undefined)).toBe('—')
    expect(formatTimestamp('')).toBe('—')
    expect(formatTimestamp('not-a-date')).toBe('—')
  })

  it('formats to second precision', () => {
    expect(formatTimestamp('2026-10-29T08:31:02.114')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
  })
})

describe('formatBucketLabel', () => {
  it('uses month-day and hour:minute', () => {
    expect(formatBucketLabel('2026-10-29T08:31:00')).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
  })
})

describe('latency labels', () => {
  it('names the first, middle and open-ended buckets', () => {
    expect(formatLatencyBucket(50, null)).toBe('≤ 50ms')
    expect(formatLatencyBucket(500, 250)).toBe('250ms–500ms')
    expect(formatLatencyBucket(null, 10000)).toBe('> 10s')
  })
})

describe('number formatting', () => {
  it('distinguishes "not measured" from zero', () => {
    expect(formatMillis(null)).toBe('—')
    expect(formatMillis(0)).toBe('0 ms')
    expect(formatCount(undefined)).toBe('—')
    expect(formatCount(1234)).toBe('1,234')
    expect(formatPercent(null)).toBe('—')
    expect(formatPercent(98.765)).toBe('98.77%')
  })
})
