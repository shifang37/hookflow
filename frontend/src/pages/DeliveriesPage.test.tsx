import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { BatchReplay, Delivery, DeliveryAttempt, DeliveryDetail } from '@/api/types'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useDeliveries: vi.fn(),
    useDeliveryDetail: vi.fn(),
    useReplayDelivery: vi.fn(),
    useBatchReplayDeliveries: vi.fn(),
  }
})

import {
  useBatchReplayDeliveries,
  useDeliveries,
  useDeliveryDetail,
  useReplayDelivery,
} from '@/api/queries'
import { toApiTimestamp } from '@/api/time'
import { DeliveriesPage } from './DeliveriesPage'

const mockedUseDeliveries = vi.mocked(useDeliveries)
const mockedUseDeliveryDetail = vi.mocked(useDeliveryDetail)
const mockedUseReplay = vi.mocked(useReplayDelivery)
const mockedUseBatchReplay = vi.mocked(useBatchReplayDeliveries)

function delivery(overrides: Partial<Delivery> = {}): Delivery {
  return {
    id: 7,
    eventId: 12,
    endpointId: 3,
    status: 'DEAD',
    attemptCount: 8,
    maxAttempts: 8,
    nextAttemptAt: null,
    lastErrorClass: 'SERVER_5XX',
    lastStatusCode: 500,
    createdAt: '2026-10-29T08:00:00.000',
    updatedAt: '2026-10-29T08:20:00.000',
    ...overrides,
  }
}

function withDeliveries(items: Delivery[]) {
  mockedUseDeliveries.mockReturnValue({
    data: { items, page: 0, size: 20, total: items.length },
    isFetching: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useDeliveries>)
}

const attempt: DeliveryAttempt = {
  attemptNo: 1,
  status: 'SUCCEEDED',
  statusCode: 200,
  errorClass: null,
  durationMs: 321,
  responsePreview: '{"ok":true}',
  startedAt: '2026-10-29T08:19:00.000',
  finishedAt: '2026-10-29T08:19:00.321',
}

const detail: DeliveryDetail = {
  delivery: delivery(),
  attempts: [attempt],
}

function withDeliveryDetail(data: DeliveryDetail | undefined) {
  mockedUseDeliveryDetail.mockReturnValue({
    data,
    error: null,
  } as unknown as ReturnType<typeof useDeliveryDetail>)
}

describe('DeliveriesPage', () => {
  beforeEach(() => {
    mockedUseDeliveries.mockReset()
    mockedUseDeliveryDetail.mockReset()
    mockedUseReplay.mockReset()
    mockedUseBatchReplay.mockReset()
    withDeliveryDetail(undefined)
    mockedUseReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useReplayDelivery>)
    mockedUseBatchReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)
    useUiStore.setState({ tenantToken: 'hk_test' })
  })

  it('renders status, attempt budget and the last error', () => {
    withDeliveries([delivery()])

    renderWithProviders(<DeliveriesPage />)

    expect(screen.getByText('死信')).toBeInTheDocument()
    // The attempt budget is rendered as `8` + a nested ` / 8`, and RTL's text matcher only
    // reads an element's *direct* text nodes — so assert on the row's whole content.
    const row = screen.getByText('死信').closest('tr')
    expect(row).not.toBeNull()
    expect(row).toHaveTextContent('8 / 8')
    expect(row).toHaveTextContent('SERVER_5XX · 500')
  })

  it('opens the delivery detail drawer with the attempt history', async () => {
    withDeliveries([delivery()])
    withDeliveryDetail(detail)

    renderWithProviders(<DeliveriesPage />)
    fireEvent.click(screen.getByRole('button', { name: /详\s*情/ }))

    expect(await screen.findByText('投递 #7')).toBeInTheDocument()
    expect(screen.getByText('尝试历史（1）')).toBeInTheDocument()
    expect(screen.getByText('321 ms')).toBeInTheDocument()
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument()
    // A fixed 900px drawer would make a 390px viewport scroll sideways.
    expect(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper')?.style.width).toBe(
      'min(900px, 100vw)',
    )
  })

  it('only offers replay on DEAD rows, mirroring the backend state machine', () => {
    withDeliveries([
      delivery({ id: 7, status: 'DEAD' }),
      delivery({ id: 8, status: 'SUCCEEDED', lastErrorClass: null, lastStatusCode: null }),
    ])

    renderWithProviders(<DeliveriesPage />)

    // AntD renders two-character CJK labels with a space between them (`重 放`).
    const [deadReplay, succeededReplay] = screen.getAllByRole('button', { name: /重\s*放/ })
    expect(deadReplay).toBeEnabled()
    expect(succeededReplay).toBeDisabled()
  })

  it('asks for confirmation before replaying', () => {
    withDeliveries([delivery({ id: 7, status: 'DEAD' })])

    renderWithProviders(<DeliveriesPage />)
    fireEvent.click(screen.getByRole('button', { name: /重\s*放/ }))

    expect(screen.getByText('重放这条投递？')).toBeInTheDocument()
  })

  it('shows the empty state rather than an error when nothing matches', () => {
    withDeliveries([])

    renderWithProviders(<DeliveriesPage />)

    expect(screen.getByText('没有匹配的投递。')).toBeInTheDocument()
  })

  it('only lets DEAD rows be selected for a batch, mirroring the state machine', () => {
    withDeliveries([
      delivery({ id: 7, status: 'DEAD' }),
      delivery({ id: 8, status: 'SUCCEEDED', lastErrorClass: null, lastStatusCode: null }),
    ])

    renderWithProviders(<DeliveriesPage />)

    // Index 0 is the header's select-all; the rows follow in data order.
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes[1]).toBeEnabled()
    expect(checkboxes[2]).toBeDisabled()
  })

  it('batch-replays the selection and reports a partial result', async () => {
    const partial: BatchReplay = {
      replayed: 1,
      rejected: 1,
      results: [
        { deliveryId: 7, replayed: true, status: 'PENDING', code: null, message: null },
        {
          deliveryId: 8,
          replayed: false,
          status: null,
          code: 40903,
          message: 'Only DEAD deliveries can be replayed (current: SUCCEEDED)',
        },
      ],
    }
    const mutate = vi.fn(
      (_ids: number[], options?: { onSuccess?: (result: BatchReplay) => void }) => {
        options?.onSuccess?.(partial)
      },
    )
    mockedUseBatchReplay.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)
    withDeliveries([delivery({ id: 7, status: 'DEAD' })])

    renderWithProviders(<DeliveriesPage />)

    fireEvent.click(screen.getAllByRole('checkbox')[1] as HTMLElement)
    fireEvent.click(screen.getByRole('button', { name: /批量重放/ }))
    fireEvent.click(screen.getByRole('button', { name: '确认重放' }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(mutate.mock.calls[0]?.[0]).toEqual([7])
    // The batch endpoint's answer is a partial result; a silent "done" would hide the
    // rejected id, which is the one the operator has to act on.
    expect(await screen.findByText(/1 条被拒绝/)).toBeInTheDocument()
  })

  it('filters by a time window in UTC and drops it with the other filters', () => {
    withDeliveries([delivery()])

    renderWithProviders(<DeliveriesPage />)

    // Focus is explicit: jsdom does not move it on a mouse event, and the picker only
    // treats Enter as a submit while its input is focused.
    const start = screen.getByPlaceholderText('开始时间')
    const end = screen.getByPlaceholderText('结束时间')
    fireEvent.mouseDown(start)
    fireEvent.focus(start)
    fireEvent.change(start, { target: { value: '2026-10-29 00:00:00' } })
    fireEvent.keyDown(start, { key: 'Enter', code: 'Enter', keyCode: 13 })
    fireEvent.change(end, { target: { value: '2026-10-30 00:00:00' } })
    fireEvent.keyDown(end, { key: 'Enter', code: 'Enter', keyCode: 13 })

    const lastQuery = () => {
      const calls = mockedUseDeliveries.mock.calls
      return calls[calls.length - 1]?.[0]
    }
    expect(lastQuery()).toMatchObject({
      from: toApiTimestamp(new Date(2026, 9, 29, 0, 0, 0)),
      to: toApiTimestamp(new Date(2026, 9, 30, 0, 0, 0)),
      page: 0,
    })

    // A window is a filter like the others: the one "clear" button must drop it too, or
    // the table keeps showing a slice while the controls say nothing is applied.
    fireEvent.click(screen.getByRole('button', { name: '清除筛选' }))
    expect(lastQuery()?.from).toBeUndefined()
    expect(lastQuery()?.to).toBeUndefined()
  })
})
