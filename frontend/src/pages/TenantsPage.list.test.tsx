import { fireEvent, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { acme, acmeStats, resetTenantPageMocks, withStats, withTenants } from '@/test/tenantFixtures'
import { useUiStore } from '@/stores/uiStore'
import { TenantsPage } from './TenantsPage'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useTenants: vi.fn(),
    useTenantStats: vi.fn(),
    useUpdateTenant: vi.fn(),
    useTenantKeys: vi.fn(),
    useIssueTenantKey: vi.fn(),
    useRevokeApiKey: vi.fn(),
    useCreateTenant: vi.fn(),
  }
})

describe('TenantsPage', () => {
  beforeEach(resetTenantPageMocks)

  it('lists tenants with their policy and counts', () => {
    withTenants([acme])

    renderWithProviders(<TenantsPage />)

    expect(screen.getByText('acme')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument()
  })

  it('opens the drawer with the tenant statistics on row click', () => {
    withTenants([acme])
    withStats(acmeStats)

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))

    expect(screen.getByText('近 24h 统计')).toBeInTheDocument()
    expect(screen.getByText('1,240')).toBeInTheDocument()
    expect(screen.getByText('98.77%')).toBeInTheDocument()
    expect(screen.getByText('812 ms')).toBeInTheDocument()
    expect(screen.getByText(/SERVER_5XX · 500/)).toBeInTheDocument()
  })

  it('says "no dead letters" instead of rendering an empty list', () => {
    withTenants([acme])
    withStats({ ...acmeStats, topFailureReasons: [] })

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))

    expect(screen.getByText('窗口内没有死信')).toBeInTheDocument()
  })

  it('warns when the operator token is missing', () => {
    useUiStore.setState({ adminToken: '' })
    withTenants([])

    renderWithProviders(<TenantsPage />)

    expect(screen.getByText('尚未配置运营令牌')).toBeInTheDocument()
  })
})
