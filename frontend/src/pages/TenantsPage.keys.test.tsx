import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MutateOptions } from '@tanstack/react-query'
import { renderWithProviders } from '@/test/render'
import { acme, activeKey, resetTenantPageMocks, revokedKey, withKeys, withTenants } from '@/test/tenantFixtures'
import { useIssueTenantKey } from '@/api/queries'
import type { IssuedKey } from '@/api/types'
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

const mockedUseIssue = vi.mocked(useIssueTenantKey)

describe('TenantsPage', () => {
  beforeEach(resetTenantPageMocks)

  it('lists live and revoked keys side by side, so a rotation is auditable', () => {
    withTenants([acme])
    withKeys([activeKey, revokedKey])

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))

    expect(screen.getByText('hk_newkey01_…')).toBeInTheDocument()
    expect(screen.getByText('hk_oldkey01_…')).toBeInTheDocument()
    expect(screen.getByText('有效')).toBeInTheDocument()
    expect(screen.getByText('已撤销')).toBeInTheDocument()
  })

  it('only allows revoking a key that is still active', () => {
    withTenants([acme])
    withKeys([activeKey, revokedKey])

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))

    const [activeRevoke, revokedRevoke] = screen.getAllByRole('button', { name: /撤\s*销/ })
    expect(activeRevoke).toBeEnabled()
    expect(revokedRevoke).toBeDisabled()
  })

  it('shows a freshly issued key exactly once', async () => {
    const issued: IssuedKey = {
      tenantId: 1,
      name: 'acme',
      apiKey: 'hk_newkey01_s3cr3t',
      keyPrefix: 'newkey01',
    }
    const mutate = vi.fn(
      (
        _variables: void,
        options?: MutateOptions<IssuedKey, Error, void, unknown>,
      ) => {
        options?.onSuccess?.(issued, undefined, undefined, {} as never)
      },
    )
    mockedUseIssue.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useIssueTenantKey>)
    withTenants([acme])

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))
    fireEvent.click(screen.getByRole('button', { name: '生成新 Key' }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('API Key 只显示这一次')).toBeInTheDocument()
    expect(screen.getByLabelText('api key')).toHaveValue('hk_newkey01_s3cr3t')
  })

  it('asks before revoking, because it cannot be undone', () => {
    withTenants([acme])
    withKeys([activeKey])

    renderWithProviders(<TenantsPage />)
    fireEvent.click(screen.getByText('acme'))
    fireEvent.click(screen.getByRole('button', { name: /撤\s*销/ }))

    expect(screen.getByText('撤销这把 Key？')).toBeInTheDocument()
    expect(screen.getByText(/用它发出的请求会开始返回 401/)).toBeInTheDocument()
  })
})
