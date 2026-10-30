import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MutateOptions } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { renderWithProviders } from '@/test/render'
import { resetTenantPageMocks, typing, withTenants } from '@/test/tenantFixtures'
import { useCreateTenant } from '@/api/queries'
import type { ProvisionTenantRequest, TenantProvisioned } from '@/api/types'
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

const mockedUseCreateTenant = vi.mocked(useCreateTenant)

describe('TenantsPage', () => {
  beforeEach(resetTenantPageMocks)

  it('rejects a blank tenant name before the round trip', async () => {
    const user = typing()
    const mutate = vi.fn()
    mockedUseCreateTenant.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useCreateTenant>)
    withTenants([])

    renderWithProviders(<TenantsPage />)
    await user.click(screen.getByRole('button', { name: /创建租户/ }))
    // Two Chinese characters: AntD renders the modal's OK button as `创 建`.
    await user.click(screen.getByRole('button', { name: /^创\s*建$/ }))

    expect(await screen.findByText('请输入租户名称')).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  }, 60_000)

  it('creates a tenant and reveals its first key exactly once', async () => {
    const user = typing()
    const created: TenantProvisioned = {
      tenantId: 7,
      name: 'ab',
      apiKey: 'hk_abc123_secret',
      keyPrefix: 'abc123',
    }
    const mutate = vi.fn(
      (
        body: ProvisionTenantRequest,
        options?: MutateOptions<TenantProvisioned, Error, ProvisionTenantRequest, unknown>,
      ) => {
        options?.onSuccess?.(created, body, undefined, {} as never)
      },
    )
    mockedUseCreateTenant.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useCreateTenant>)
    withTenants([])

    renderWithProviders(<TenantsPage />)
    await user.click(screen.getByRole('button', { name: /创建租户/ }))
    await user.type(screen.getByPlaceholderText('acme'), 'ab')
    await user.click(screen.getByRole('button', { name: /^创\s*建$/ }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(mutate.mock.calls[0]?.[0]).toMatchObject({
      name: 'ab',
      rateLimit: 100,
      maxInFlight: 32,
      weight: 1,
    })

    expect(await screen.findByText('租户已创建')).toBeInTheDocument()
    expect(screen.getByLabelText('provisioned api key')).toHaveValue('hk_abc123_secret')
  }, 60_000)

  it('shows the backend conflict message and leaves the form open to fix the name', async () => {
    const user = typing()
    const mutate = vi.fn(
      (
        body: ProvisionTenantRequest,
        options?: MutateOptions<TenantProvisioned, Error, ProvisionTenantRequest, unknown>,
      ) => {
        options?.onError?.(
          new ApiError('tenant name already exists: ab', { status: 409 }),
          body,
          undefined,
          {} as never,
        )
      },
    )
    mockedUseCreateTenant.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useCreateTenant>)
    withTenants([])

    renderWithProviders(<TenantsPage />)
    await user.click(screen.getByRole('button', { name: /创建租户/ }))
    await user.type(screen.getByPlaceholderText('acme'), 'ab')
    await user.click(screen.getByRole('button', { name: /^创\s*建$/ }))

    expect(await screen.findByText('tenant name already exists: ab')).toBeInTheDocument()
    // The input is still there: a failed create must not throw away what was typed.
    expect(screen.getByPlaceholderText('acme')).toBeInTheDocument()
  }, 60_000)
})
