import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import type { ApiKey, Tenant, TenantStats } from '@/api/types'
import {
  useCreateTenant,
  useIssueTenantKey,
  useRevokeApiKey,
  useTenantKeys,
  useTenants,
  useTenantStats,
  useUpdateTenant,
} from '@/api/queries'
import { useUiStore } from '@/stores/uiStore'

/**
 * Fixtures and mock wiring shared by the three `TenantsPage` test files.
 *
 * Vitest parallelises across files but runs the tests inside one file serially, so a
 * single page test that opens a drawer, edits a form and provisions a tenant becomes the
 * suite's critical path: it was ~54s of a 60s run, and ~127s of the 234s of instrumented
 * test time under v8 coverage. Splitting by topic (`list` / `keys` / `create`) is what
 * buys the parallelism; this module keeps the three files from each growing a copy of the
 * fixtures and the default mock returns.
 *
 * The `vi.mock('@/api/queries')` call itself cannot live here: Vitest hoists it above the
 * imports of the file it appears in, and every test file has to declare its own. Because
 * that hoisting happens before this module is imported, importing the hooks below is safe.
 */

export const acme: Tenant = {
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

export const acmeStats: TenantStats = {
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

export const activeKey: ApiKey = {
  id: 11,
  keyPrefix: 'newkey01',
  scopes: 'event:write',
  status: 'ACTIVE',
  createdAt: '2026-10-29T08:00:00.000',
  revokedAt: null,
}

export const revokedKey: ApiKey = {
  id: 10,
  keyPrefix: 'oldkey01',
  scopes: 'event:write',
  status: 'REVOKED',
  createdAt: '2026-10-01T08:00:00.000',
  revokedAt: '2026-10-29T07:59:00.000',
}

/** `delay: null` skips userEvent's per-keystroke tick; AntD's Form re-render is the slow part. */
export const typing = () => userEvent.setup({ delay: null })

export function withTenants(items: Tenant[]): void {
  vi.mocked(useTenants).mockReturnValue({
    data: { items, page: 0, size: 20, total: items.length },
    isFetching: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useTenants>)
}

export function withStats(stats: TenantStats | undefined): void {
  vi.mocked(useTenantStats).mockReturnValue({
    data: stats,
    error: null,
  } as unknown as ReturnType<typeof useTenantStats>)
}

export function withKeys(keys: ApiKey[]): void {
  vi.mocked(useTenantKeys).mockReturnValue({
    data: keys,
    error: null,
  } as unknown as ReturnType<typeof useTenantKeys>)
}

/**
 * The state every test starts from: all seven hooks reset, the mutations idle, no stats,
 * no keys, and the operator token present.
 *
 * `mockReset` on all of them matters more than it looks: a leftover `mockReturnValue`
 * from the previous test would make the next one pass for the wrong reason, and each file
 * runs its own tests in sequence.
 */
export function resetTenantPageMocks(): void {
  vi.mocked(useTenants).mockReset()
  vi.mocked(useTenantStats).mockReset()
  vi.mocked(useUpdateTenant).mockReset()
  vi.mocked(useTenantKeys).mockReset()
  vi.mocked(useIssueTenantKey).mockReset()
  vi.mocked(useRevokeApiKey).mockReset()
  vi.mocked(useCreateTenant).mockReset()

  vi.mocked(useCreateTenant).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useCreateTenant>)
  vi.mocked(useUpdateTenant).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useUpdateTenant>)
  vi.mocked(useTenantStats).mockReturnValue({
    data: undefined,
    error: null,
  } as unknown as ReturnType<typeof useTenantStats>)
  vi.mocked(useTenantKeys).mockReturnValue({
    data: [],
    error: null,
  } as unknown as ReturnType<typeof useTenantKeys>)
  vi.mocked(useIssueTenantKey).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useIssueTenantKey>)
  vi.mocked(useRevokeApiKey).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useRevokeApiKey>)

  useUiStore.setState({ tenantToken: '', adminToken: 'admin-token', range: '24h' })
}
