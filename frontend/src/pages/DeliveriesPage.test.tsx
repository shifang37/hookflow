import { fireEvent, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { Delivery } from '@/api/types'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return { ...actual, useDeliveries: vi.fn(), useReplayDelivery: vi.fn() }
})

import { useDeliveries, useReplayDelivery } from '@/api/queries'
import { DeliveriesPage } from './DeliveriesPage'

const mockedUseDeliveries = vi.mocked(useDeliveries)
const mockedUseReplay = vi.mocked(useReplayDelivery)

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
    mockedUseReplay.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useReplayDelivery>)
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
})
