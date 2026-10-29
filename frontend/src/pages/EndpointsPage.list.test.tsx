import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { activeEndpoint, pausedEndpoint, resetEndpointPageMocks, withEndpoints } from '@/test/endpointFixtures'
import { useUiStore } from '@/stores/uiStore'
import { EndpointsPage } from './EndpointsPage'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useEndpoints: vi.fn(),
    useCreateEndpoint: vi.fn(),
    useSetEndpointStatus: vi.fn(),
    useDeleteEndpoint: vi.fn(),
  }
})

describe('EndpointsPage', () => {
  beforeEach(resetEndpointPageMocks)

  it('lists endpoints with subscription, caps and status', () => {
    withEndpoints([activeEndpoint, pausedEndpoint])
    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText('https://customer.example/hooks/hookflow')).toBeInTheDocument()
    expect(screen.getByText('全部事件')).toBeInTheDocument()
    expect(screen.getByText('order.paid')).toBeInTheDocument()
    expect(screen.getByText('order.refunded')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('已暂停')).toBeInTheDocument()
    // 0 means "no endpoint-level cap"; rendering it as "0" would read like a limit.
    expect(screen.getByText('不限')).toBeInTheDocument()
  })

  it('offers pause on active rows and resume on paused ones', () => {
    withEndpoints([activeEndpoint, pausedEndpoint])
    renderWithProviders(<EndpointsPage />)

    // AntD inserts a space between two CJK characters, so the names are not literal.
    expect(screen.getAllByRole('button', { name: /暂\s*停/ })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: /恢\s*复/ })).toHaveLength(1)
  })

  it('shows the empty state when the tenant has no endpoints', () => {
    withEndpoints([])
    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText(/还没有 Endpoint/)).toBeInTheDocument()
  })

  it('points at the missing credential instead of pretending the list is empty', () => {
    useUiStore.setState({ tenantToken: '' })
    withEndpoints([])
    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText('尚未配置租户 API Key')).toBeInTheDocument()
  })
})
