import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { BatchReplay, Delivery } from '@/api/types'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useDeliveries: vi.fn(),
    useReplayDelivery: vi.fn(),
    useBatchReplayDeliveries: vi.fn(),
  }
})

import { useBatchReplayDeliveries, useDeliveries, useReplayDelivery } from '@/api/queries'
import { DeliveriesPage } from './DeliveriesPage'

const mockedUseDeliveries = vi.mocked(useDeliveries)
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

describe('DeliveriesPage', () => {
  beforeEach(() => {
    mockedUseDeliveries.mockReset()
    mockedUseReplay.mockReset()
    mockedUseBatchReplay.mockReset()
    mockedUseReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useReplayDelivery>)
    mockedUseBatchReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useBatchReplayDeliveries>)
    useUiStore.setState({ tenantToken: 'hk_test', adminToken: '' })
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
})
