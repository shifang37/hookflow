import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { acme, acmeStats, resetTenantPageMocks, withStats, withTenants } from '@/test/tenantFixtures'
import { useUpdateTenant } from '@/api/queries'
import { useUiStore } from '@/stores/uiStore'
import type { Tenant } from '@/api/types'
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
    expect(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper')?.style.width).toBe(
      'min(520px, 100vw)',
    )
  })

  it('suspends an active tenant from the drawer', async () => {
    const mutate = vi.fn(
      (
        _args: { tenantId: number; body: { status: string } },
        options?: { onSuccess?: (tenant: Tenant) => void },
      ) => {
        options?.onSuccess?.({ ...acme, status: 'SUSPENDED' })
      },
    )
    vi.mocked(useUpdateTenant).mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useUpdateTenant>)
    withTenants([acme])
    withStats(acmeStats)

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))
    fireEvent.click(screen.getByRole('button', { name: '停用租户' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认停用' }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(mutate.mock.calls[0]?.[0]).toEqual({
      tenantId: 1,
      body: { status: 'SUSPENDED' },
    })
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
