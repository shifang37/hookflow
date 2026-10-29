import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MutateOptions } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type {
  ApiKey,
  IssuedKey,
  ProvisionTenantRequest,
  Tenant,
  TenantProvisioned,
  TenantStats,
} from '@/api/types'

/** `delay: null` skips userEvent's per-keystroke tick; AntD's Form re-render is the slow part. */
const typing = () => userEvent.setup({ delay: null })

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

import {
  useCreateTenant,
  useIssueTenantKey,
  useRevokeApiKey,
  useTenantKeys,
  useTenants,
  useTenantStats,
  useUpdateTenant,
} from '@/api/queries'
import { TenantsPage } from './TenantsPage'

const mockedUseTenants = vi.mocked(useTenants)
const mockedUseStats = vi.mocked(useTenantStats)
const mockedUseUpdate = vi.mocked(useUpdateTenant)
const mockedUseKeys = vi.mocked(useTenantKeys)
const mockedUseIssue = vi.mocked(useIssueTenantKey)
const mockedUseRevoke = vi.mocked(useRevokeApiKey)
const mockedUseCreateTenant = vi.mocked(useCreateTenant)

const acme: Tenant = {
  tenantId: 1,
  name: 'acme',
  status: 'ACTIVE',
  rateLimit: 100,
  maxInFlight: 32,
  weight: 2,
  endpointCount: 3,
  activeKeyCount: 2,
  createdAt: '2026-10-20T03:11:08.221',
}

const acmeStats: TenantStats = {
  tenantId: 1,
  range: '24h',
  totalEvents: 1240,
  deliveries: 2480,
  succeeded: 2400,
  dead: 30,
  canceled: 50,
  inFlight: 0,
  successRate: 98.77,
  avgLatencyMs: 812,
  backlog: 4,
  topFailureReasons: [
    { errorClass: 'SERVER_5XX', statusCode: 500, count: 20, percentage: 66.67 },
  ],
}

function withTenants(items: Tenant[]) {
  mockedUseTenants.mockReturnValue({
    data: { items, page: 0, size: 20, total: items.length },
    isFetching: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useTenants>)
}

const activeKey: ApiKey = {
  id: 11,
  keyPrefix: 'newkey01',
  scopes: 'event:write',
  status: 'ACTIVE',
  createdAt: '2026-10-29T08:00:00.000',
  revokedAt: null,
}

const revokedKey: ApiKey = {
  id: 10,
  keyPrefix: 'oldkey01',
  scopes: 'event:write',
  status: 'REVOKED',
  createdAt: '2026-10-01T08:00:00.000',
  revokedAt: '2026-10-29T07:59:00.000',
}

function withKeys(keys: ApiKey[]) {
  mockedUseKeys.mockReturnValue({
    data: keys,
    error: null,
  } as unknown as ReturnType<typeof useTenantKeys>)
}

describe('TenantsPage', () => {
  beforeEach(() => {
    mockedUseTenants.mockReset()
    mockedUseStats.mockReset()
    mockedUseUpdate.mockReset()
    mockedUseKeys.mockReset()
    mockedUseIssue.mockReset()
    mockedUseRevoke.mockReset()
    mockedUseCreateTenant.mockReset()
    mockedUseCreateTenant.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useCreateTenant>)
    mockedUseUpdate.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useUpdateTenant>)
    mockedUseStats.mockReturnValue({
      data: undefined,
      error: null,
    } as unknown as ReturnType<typeof useTenantStats>)
    mockedUseKeys.mockReturnValue({
      data: [],
      error: null,
    } as unknown as ReturnType<typeof useTenantKeys>)
    mockedUseIssue.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useIssueTenantKey>)
    mockedUseRevoke.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useRevokeApiKey>)
    useUiStore.setState({ tenantToken: '', adminToken: 'admin-token', range: '24h' })
  })

  it('lists tenants with their policy and counts', () => {
    withTenants([acme])

    renderWithProviders(<TenantsPage />)

    expect(screen.getByText('acme')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument()
  })

  it('opens the drawer with the tenant statistics on row click', () => {
    withTenants([acme])
    mockedUseStats.mockReturnValue({
      data: acmeStats,
      error: null,
    } as unknown as ReturnType<typeof useTenantStats>)

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
    mockedUseStats.mockReturnValue({
      data: { ...acmeStats, topFailureReasons: [] },
      error: null,
    } as unknown as ReturnType<typeof useTenantStats>)

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

  // ------------------------------------------------------------- api key rotation

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

  // ------------------------------------------------------------ tenant creation

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
  }, 30_000)

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
  }, 30_000)

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
  }, 30_000)
})
