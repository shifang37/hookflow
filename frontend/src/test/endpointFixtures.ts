import { vi } from 'vitest'
import { useCreateEndpoint, useDeleteEndpoint, useEndpoints, useSetEndpointStatus } from '@/api/queries'
import type { Endpoint } from '@/api/types'
import { useUiStore } from '@/stores/uiStore'
import type { EndpointCreated } from '@/api/types'

/**
 * Fixtures for the three `EndpointsPage` test files.
 *
 * The page test was one file of ten cases; under v8 coverage it took ~85s, which made it the
 * suite's critical path once the tenants page was split. Vitest parallelises across files
 * only, so the split is by topic — list, row actions, create — and this module keeps the
 * fixtures in one place instead of three.
 *
 * The `vi.mock('@/api/queries')` factories stay in each test file: vitest hoists them above
 * the imports of the file they appear in, which is also what makes importing the hooks here
 * safe.
 */

export const activeEndpoint: Endpoint = {
  id: 1,
  url: 'https://customer.example/hooks/hookflow',
  subscribedTypes: '*',
  maxAttempts: 8,
  maxConcurrency: 0,
  status: 'ACTIVE',
  createdAt: '2026-10-29T08:00:00.000',
}

export const pausedEndpoint: Endpoint = {
  id: 2,
  url: 'https://legacy.example/hook',
  subscribedTypes: 'order.paid,order.refunded',
  maxAttempts: 5,
  maxConcurrency: 10,
  status: 'PAUSED',
  createdAt: '2026-10-28T08:00:00.000',
}

export const createdEndpoint: EndpointCreated = {
  id: 9,
  url: 'https://a.io/h',
  subscribedTypes: '*',
  maxAttempts: 8,
  maxConcurrency: 0,
  status: 'ACTIVE',
  signingSecret: 'c2VjcmV0',
}

export function withEndpoints(items: Endpoint[]): void {
  vi.mocked(useEndpoints).mockReturnValue({
    data: { items, page: 0, size: 20, total: items.length },
    isFetching: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useEndpoints>)
}

export function createResult(
  overrides: Partial<ReturnType<typeof useCreateEndpoint>> = {},
): ReturnType<typeof useCreateEndpoint> {
  return {
    mutate: vi.fn(),
    isPending: false,
    ...overrides,
  } as unknown as ReturnType<typeof useCreateEndpoint>
}

/** The state every test starts from: four hooks reset, mutations idle, tenant credential set. */
export function resetEndpointPageMocks(): void {
  vi.mocked(useEndpoints).mockReset()
  vi.mocked(useCreateEndpoint).mockReset()
  vi.mocked(useSetEndpointStatus).mockReset()
  vi.mocked(useDeleteEndpoint).mockReset()

  vi.mocked(useCreateEndpoint).mockReturnValue(createResult())
  vi.mocked(useSetEndpointStatus).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useSetEndpointStatus>)
  vi.mocked(useDeleteEndpoint).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useDeleteEndpoint>)

  useUiStore.setState({ tenantToken: 'hk_test', adminToken: '' })
}
