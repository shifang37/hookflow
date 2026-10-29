import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'

// Only the hook is replaced: `blankToUndefined` and `POLL_INTERVAL_MS` are the page's
// real collaborators and are exercised through it.
vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return { ...actual, useEvents: vi.fn() }
})

import { useEvents } from '@/api/queries'
import { ApiError } from '@/api/client'
import { EventStreamPage } from './EventStreamPage'

const mockedUseEvents = vi.mocked(useEvents)

type EventsResult = ReturnType<typeof useEvents>

function asResult(overrides: Record<string, unknown>): EventsResult {
  return {
    refetch: vi.fn(),
    isFetching: false,
    error: null,
    data: undefined,
    ...overrides,
  } as unknown as EventsResult
}

describe('EventStreamPage', () => {
  beforeEach(() => {
    mockedUseEvents.mockReset()
    // A credential is present so the page renders its table, not the credential banner.
    useUiStore.setState({ tenantToken: 'hk_test_secret', adminToken: '' })
  })

  it('renders one row per event with its delivery roll-up', () => {
    mockedUseEvents.mockReturnValue(
      asResult({
        data: {
          items: [
            {
              eventId: 12,
              eventType: 'order.paid',
              deliveryCount: 2,
              succeededCount: 2,
              failedCount: 0,
              inFlightCount: 0,
              createdAt: '2026-10-29T08:31:02.114',
            },
          ],
          page: 0,
          size: 20,
          total: 1,
        },
      }),
    )

    renderWithProviders(<EventStreamPage />)

    expect(screen.getByText('order.paid')).toBeInTheDocument()
    expect(screen.getByText('2 成功')).toBeInTheDocument()
    expect(screen.getByText('每 2 秒刷新')).toBeInTheDocument()
  })

  it('shows the empty-state hint, not an error, when the tenant has no events', () => {
    mockedUseEvents.mockReturnValue(asResult({ data: { items: [], page: 0, size: 20, total: 0 } }))

    renderWithProviders(<EventStreamPage />)

    expect(screen.getByText(/还没有事件/)).toBeInTheDocument()
  })

  it('surfaces a missing credential as a configuration warning', () => {
    useUiStore.setState({ tenantToken: '' })
    mockedUseEvents.mockReturnValue(asResult({ data: undefined }))

    renderWithProviders(<EventStreamPage />)

    expect(screen.getByText('尚未配置租户 API Key')).toBeInTheDocument()
  })

  it('renders the backend message and requestId for a 500', () => {
    mockedUseEvents.mockReturnValue(
      asResult({
        error: new ApiError('boom', { status: 500, requestId: 'req-42' }),
      }),
    )

    renderWithProviders(<EventStreamPage />)

    expect(screen.getByText('读取事件失败')).toBeInTheDocument()
    expect(screen.getByText('boom')).toBeInTheDocument()
    expect(screen.getByText(/req-42/)).toBeInTheDocument()
  })
})
