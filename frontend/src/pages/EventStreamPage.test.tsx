import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { EventDetail, EventSummary } from '@/api/types'

// Only the hooks are replaced: `blankToUndefined` and `POLL_INTERVAL_MS` are the page's
// real collaborators and are exercised through it.
vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useEvents: vi.fn(),
    useEventDetail: vi.fn(),
    useBatchReplayDeliveries: vi.fn(),
    fetchEventDetail: vi.fn(),
  }
})

import {
  fetchEventDetail,
  useBatchReplayDeliveries,
  useEventDetail,
  useEvents,
} from '@/api/queries'
import { ApiError } from '@/api/client'
import { toApiTimestamp } from '@/api/time'
import { EventStreamPage } from './EventStreamPage'

const mockedUseEvents = vi.mocked(useEvents)
const mockedUseDetail = vi.mocked(useEventDetail)
const mockedUseBatchReplay = vi.mocked(useBatchReplayDeliveries)
const mockedFetchDetail = vi.mocked(fetchEventDetail)

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

function event(eventId: number): EventSummary {
  return {
    eventId,
    eventType: 'order.paid',
    deliveryCount: 2,
    succeededCount: 1,
    failedCount: 1,
    inFlightCount: 0,
    createdAt: '2026-10-29T08:31:02.114',
  }
}

function pageOf(items: EventSummary[]) {
  return { items, page: 0, size: 20, total: items.length }
}

function detail(eventId: number, payload: string, deliveries: EventDetail['deliveries']): EventDetail {
  return {
    eventId,
    eventType: 'order.paid',
    payload,
    createdAt: '2026-10-29T08:31:02.114',
    deliveryCount: deliveries.length,
    deliveries,
  }
}

function delivery(id: number, status: EventDetail['deliveries'][number]['status']) {
  return {
    id,
    eventId: 12,
    endpointId: 3,
    status,
    attemptCount: 8,
    maxAttempts: 8,
    nextAttemptAt: null,
    lastErrorClass: 'SERVER_5XX',
    lastStatusCode: 500,
    createdAt: '2026-10-29T08:31:02.114',
    updatedAt: '2026-10-29T08:41:02.114',
  }
}

describe('EventStreamPage', () => {
  beforeEach(() => {
    mockedUseEvents.mockReset()
    mockedUseDetail.mockReset()
    mockedUseBatchReplay.mockReset()
    mockedFetchDetail.mockReset()
    mockedUseDetail.mockReturnValue({
      data: undefined,
      error: null,
      isFetching: false,
    } as unknown as ReturnType<typeof useEventDetail>)
    mockedUseBatchReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)
    // A credential is present so the page renders its table, not the credential banner.
    useUiStore.setState({ tenantToken: 'hk_test_secret' })
  })

  it('renders one row per event with its delivery roll-up', () => {
    mockedUseEvents.mockReturnValue(asResult({ data: pageOf([event(12)]) }))

    renderWithProviders(<EventStreamPage />)

    expect(screen.getByText('order.paid')).toBeInTheDocument()
    expect(screen.getByText('1 成功')).toBeInTheDocument()
    expect(screen.getByText('1 失败')).toBeInTheDocument()
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

  it('opens the drawer with the pretty-printed payload and the event fan-out', () => {
    mockedUseEvents.mockReturnValue(asResult({ data: pageOf([event(12)]) }))
    mockedUseDetail.mockReturnValue({
      data: detail(12, '{"amount":42}', [delivery(100, 'DEAD')]),
      error: null,
      isFetching: false,
    } as unknown as ReturnType<typeof useEventDetail>)

    renderWithProviders(<EventStreamPage />)
    fireEvent.click(screen.getByText('order.paid'))

    expect(screen.getByText('事件 #12')).toBeInTheDocument()
    // The payload arrives as raw JSON text; the drawer is what makes it readable.
    expect(screen.getByText(/"amount": 42/)).toBeInTheDocument()
    expect(screen.getByText('死信')).toBeInTheDocument()
    expect(screen.getByText(/SERVER_5XX · 500/)).toBeInTheDocument()
    expect(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper')?.style.width).toBe(
      'min(760px, 100vw)',
    )
  })

  it('resolves a multi-event selection into the DEAD delivery ids it replays', async () => {
    mockedUseEvents.mockReturnValue(asResult({ data: pageOf([event(12), event(13)]) }))
    mockedFetchDetail.mockImplementation((eventId) =>
      Promise.resolve(
        eventId === 12
          ? detail(12, '{}', [delivery(100, 'DEAD'), delivery(101, 'SUCCEEDED')])
          : detail(13, '{}', [delivery(102, 'DEAD')]),
      ),
    )
    const mutate = vi.fn()
    mockedUseBatchReplay.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)

    renderWithProviders(<EventStreamPage />)

    // Index 0 is the header's select-all; the next two are the rows.
    const checkboxes = screen.getAllByRole('checkbox')
    fireEvent.click(checkboxes[1] as HTMLElement)
    fireEvent.click(checkboxes[2] as HTMLElement)

    fireEvent.click(screen.getByRole('button', { name: /批量重放/ }))
    fireEvent.click(screen.getByRole('button', { name: '确认重放' }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(mutate.mock.calls[0]?.[0]).toEqual([100, 102])
  })

  it('says so when the selected events have no dead letters', async () => {
    mockedUseEvents.mockReturnValue(asResult({ data: pageOf([event(12)]) }))
    mockedFetchDetail.mockResolvedValue(detail(12, '{}', [delivery(101, 'SUCCEEDED')]))
    const mutate = vi.fn()
    mockedUseBatchReplay.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)

    renderWithProviders(<EventStreamPage />)
    fireEvent.click(screen.getAllByRole('checkbox')[1] as HTMLElement)
    fireEvent.click(screen.getByRole('button', { name: /批量重放/ }))
    fireEvent.click(screen.getByRole('button', { name: '确认重放' }))

    expect(await screen.findByText('选中的事件没有死信投递')).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it('sends a picked time window to the API as UTC and goes back to the first page', () => {
    mockedUseEvents.mockReturnValue(asResult({ data: pageOf([event(12)]) }))

    renderWithProviders(<EventStreamPage />)

    // Typing into the two inputs is how a keyboard user sets a window, and it does not
    // depend on which month the popup happens to open on. jsdom does not move focus on a
    // mouse event, and the picker only treats Enter as a submit while its input is
    // focused, so the focus is explicit.
    const start = screen.getByPlaceholderText('开始时间')
    const end = screen.getByPlaceholderText('结束时间')
    fireEvent.mouseDown(start)
    fireEvent.focus(start)
    fireEvent.change(start, { target: { value: '2026-10-29 00:00:00' } })
    fireEvent.keyDown(start, { key: 'Enter', code: 'Enter', keyCode: 13 })
    fireEvent.change(end, { target: { value: '2026-10-30 00:00:00' } })
    fireEvent.keyDown(end, { key: 'Enter', code: 'Enter', keyCode: 13 })

    const calls = mockedUseEvents.mock.calls
    const query = calls[calls.length - 1]?.[0]
    // The picker works in local time; what reaches the wire is the same instant in UTC,
    // which is why the expectation goes through the same conversion instead of a literal.
    expect(query).toMatchObject({
      from: toApiTimestamp(new Date(2026, 9, 29, 0, 0, 0)),
      to: toApiTimestamp(new Date(2026, 9, 30, 0, 0, 0)),
      page: 0,
    })
  })
})
