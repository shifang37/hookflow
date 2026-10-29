import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { Latency, Overview, Timeseries } from '@/api/types'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return { ...actual, useOverview: vi.fn(), useTimeseries: vi.fn(), useLatency: vi.fn() }
})

import { useLatency, useOverview, useTimeseries } from '@/api/queries'
import { OverviewPage } from './OverviewPage'

const mockedOverview = vi.mocked(useOverview)
const mockedTimeseries = vi.mocked(useTimeseries)
const mockedLatency = vi.mocked(useLatency)

const overview: Overview = {
  range: '24h',
  totalEvents: 1240,
  deliveries: 2480,
  succeeded: 2400,
  dead: 30,
  canceled: 50,
  inFlight: 0,
  successRate: 98.77,
  avgLatencyMs: 812,
  backlog: 4,
  activeTenants: 2,
  activeEndpoints: 5,
}

const series: Timeseries = {
  range: '24h',
  interval: '15m',
  bucketSeconds: 900,
  from: '2026-10-28T09:15',
  to: '2026-10-29T09:15',
  points: [
    {
      bucketStart: '2026-10-29T08:00',
      events: 10,
      attempts: 12,
      succeeded: 8,
      dead: 1,
      canceled: 0,
    },
  ],
}

const latency: Latency = {
  range: '24h',
  samples: 120,
  p50Ms: 12,
  p95Ms: 480,
  p99Ms: 1200,
  buckets: [
    { upperBoundMs: 50, count: 100, percentage: 83.33 },
    { upperBoundMs: 500, count: 18, percentage: 15.0 },
    { upperBoundMs: null, count: 2, percentage: 1.67 },
  ],
}

function withData({
  overviewData = overview,
  seriesData = series,
  latencyData = latency,
}: {
  overviewData?: Overview
  seriesData?: Timeseries
  latencyData?: Latency
} = {}) {
  mockedOverview.mockReturnValue({
    data: overviewData,
    error: null,
    isFetching: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useOverview>)
  mockedTimeseries.mockReturnValue({
    data: seriesData,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useTimeseries>)
  mockedLatency.mockReturnValue({
    data: latencyData,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useLatency>)
}

describe('OverviewPage', () => {
  beforeEach(() => {
    mockedOverview.mockReset()
    mockedTimeseries.mockReset()
    mockedLatency.mockReset()
    useUiStore.setState({ adminToken: 'admin-token', tenantToken: '', range: '24h', interval: 'auto' })
  })

  it('renders the overview cards from the aggregate endpoint', () => {
    withData()

    renderWithProviders(<OverviewPage />)

    expect(screen.getByText('1,240')).toBeInTheDocument()
    expect(screen.getByText('98.77%')).toBeInTheDocument()
    expect(screen.getByText('812 ms')).toBeInTheDocument()
    expect(screen.getByText('活跃租户')).toBeInTheDocument()
    expect(screen.getByText('活跃 Endpoint')).toBeInTheDocument()
  })

  it('renders nearest-rank percentiles, distinguishing "fast" from "unmeasured"', () => {
    withData()

    renderWithProviders(<OverviewPage />)

    expect(screen.getByText('12 ms')).toBeInTheDocument()
    expect(screen.getByText('480 ms')).toBeInTheDocument()
    expect(screen.getByText('1200 ms')).toBeInTheDocument()
  })

  it('shows a dash instead of 0 ms when nothing was measured', () => {
    withData({
      latencyData: { ...latency, samples: 0, p50Ms: null, p95Ms: null, p99Ms: null, buckets: [] },
    })

    renderWithProviders(<OverviewPage />)

    expect(screen.getByText('窗口内没有尝试记录')).toBeInTheDocument()
    expect(screen.queryByText('0 ms')).not.toBeInTheDocument()
  })

  it('shows the empty state when the window has no buckets', () => {
    withData({ seriesData: { ...series, points: [] } })

    renderWithProviders(<OverviewPage />)

    expect(screen.getByText('窗口内没有数据')).toBeInTheDocument()
  })

  it('keeps the warning about the two different time bases visible', () => {
    withData()

    renderWithProviders(<OverviewPage />)

    expect(screen.getByText(/到达数按事件创建时间计/)).toBeInTheDocument()
  })
})
