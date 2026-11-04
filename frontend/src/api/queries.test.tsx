import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  DeliveryQuery,
  EndpointQuery,
  EventQuery,
  LatencyQuery,
  TenantQuery,
  TimeseriesQuery,
} from './client'
import type {
  ApiKey,
  BatchReplay,
  CreateEndpointRequest,
  Delivery,
  DeliveryDetail,
  Endpoint,
  EndpointCreated,
  EndpointTestResult,
  EventDetail,
  EventSummary,
  IssuedKey,
  Latency,
  Overview,
  PageResponse,
  ProvisionTenantRequest,
  Tenant,
  TenantProvisioned,
  TenantStats,
  Timeseries,
  UpdateTenantRequest,
} from './types'

/**
 * The hooks had page-level tests but no direct ones: the pages mock `queries`, so a
 * wrong query key, a missing `enabled` guard or a mutation that forgot to invalidate a
 * list would have passed the whole suite. This file exercises the real hooks against a
 * real `QueryClient`, with only the HTTP layer (`api`) mocked.
 */
vi.mock('./client', () => ({
  api: {
    listEvents: vi.fn(),
    listDeliveries: vi.fn(),
    getDelivery: vi.fn(),
    replayDelivery: vi.fn(),
    replayDeliveries: vi.fn(),
    getEvent: vi.fn(),
    listEndpoints: vi.fn(),
    createEndpoint: vi.fn(),
    pauseEndpoint: vi.fn(),
    resumeEndpoint: vi.fn(),
    testEndpoint: vi.fn(),
    deleteEndpoint: vi.fn(),
    listTenants: vi.fn(),
    createTenant: vi.fn(),
    getTenant: vi.fn(),
    getTenantStats: vi.fn(),
    updateTenant: vi.fn(),
    listTenantKeys: vi.fn(),
    issueTenantKey: vi.fn(),
    revokeApiKey: vi.fn(),
    getOverview: vi.fn(),
    getTimeseries: vi.fn(),
    getLatency: vi.fn(),
  },
}))

import { api } from './client'
import {
  POLL_INTERVAL_MS,
  blankToUndefined,
  fetchEventDetail,
  queryKeys,
  useBatchReplayDeliveries,
  useCreateEndpoint,
  useCreateTenant,
  useDeleteEndpoint,
  useDeliveries,
  useDeliveryDetail,
  useEndpoints,
  useEventDetail,
  useEvents,
  useIssueTenantKey,
  useLatency,
  useOverview,
  useReplayDelivery,
  useRevokeApiKey,
  useSetEndpointStatus,
  useTenantKeys,
  useTenants,
  useTenantStats,
  useTestEndpoint,
  useTimeseries,
  useUpdateTenant,
} from './queries'

const eventSummary: EventSummary = {
  eventId: 1,
  eventType: 'order.paid',
  deliveryCount: 1,
  succeededCount: 0,
  failedCount: 0,
  inFlightCount: 1,
  createdAt: '2026-11-04T00:00:00',
}

const delivery: Delivery = {
  id: 11,
  eventId: 1,
  endpointId: 3,
  status: 'PENDING',
  attemptCount: 0,
  maxAttempts: 6,
  nextAttemptAt: null,
  lastErrorClass: null,
  lastStatusCode: null,
  createdAt: '2026-11-04T00:00:00',
  updatedAt: '2026-11-04T00:00:00',
}

const endpoint: Endpoint = {
  id: 3,
  url: 'https://example.test/hook',
  subscribedTypes: '*',
  maxAttempts: 6,
  maxConcurrency: 0,
  status: 'ACTIVE',
  createdAt: '2026-11-04T00:00:00',
}

const tenant: Tenant = {
  tenantId: 7,
  name: 'demo-acme',
  status: 'ACTIVE',
  rateLimit: 100,
  maxInFlight: 10,
  weight: 1,
  endpointCount: 2,
  activeKeyCount: 1,
  createdAt: '2026-11-04T00:00:00',
}

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper }
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('query keys and pure helpers', () => {
  it('drops empty filter values before they become query parameters', () => {
    expect(blankToUndefined(undefined)).toBeUndefined()
    expect(blankToUndefined('')).toBeUndefined()
    expect(blankToUndefined('   ')).toBeUndefined()
    expect(blankToUndefined('  order.paid ')).toBe('order.paid')
  })

  it('derives one stable cache key per query shape', () => {
    const eventQuery: EventQuery = { eventType: 'order.paid', page: 1, size: 20 }
    const deliveryQuery: DeliveryQuery = { status: 'DEAD', page: 0, size: 20 }
    const endpointQuery: EndpointQuery = { page: 0, size: 20 }
    const tenantQuery: TenantQuery = { search: 'acme', page: 0, size: 20 }
    const timeseriesQuery: TimeseriesQuery = { range: '24h', interval: 'auto' }
    const latencyQuery: LatencyQuery = { range: '7d' }

    expect(queryKeys.events(eventQuery)).toEqual(['events', eventQuery])
    expect(queryKeys.eventDetail(1)).toEqual(['event-detail', 1])
    expect(queryKeys.deliveries(deliveryQuery)).toEqual(['deliveries', deliveryQuery])
    expect(queryKeys.deliveryDetail(11)).toEqual(['delivery-detail', 11])
    expect(queryKeys.endpoints(endpointQuery)).toEqual(['endpoints', endpointQuery])
    expect(queryKeys.tenants(tenantQuery)).toEqual(['tenants', tenantQuery])
    expect(queryKeys.tenant(7)).toEqual(['tenant', 7])
    expect(queryKeys.tenantKeys(7)).toEqual(['tenant-keys', 7])
    expect(queryKeys.tenantStats(7, '24h')).toEqual(['tenant-stats', 7, '24h'])
    expect(queryKeys.overview('24h')).toEqual(['overview', '24h'])
    expect(queryKeys.timeseries(timeseriesQuery)).toEqual(['timeseries', timeseriesQuery])
    expect(queryKeys.latency(latencyQuery)).toEqual(['latency', latencyQuery])
  })

  it('keeps the documented polling budget in one place', () => {
    expect(POLL_INTERVAL_MS).toEqual({
      eventStream: 2_000,
      deliveries: 3_000,
      endpoints: 15_000,
      tenants: 10_000,
      overview: 5_000,
      timeseries: 15_000,
      latency: 30_000,
    })
  })
})

describe('query hooks', () => {
  it('useEvents queries the event stream with its filters', async () => {
    const query: EventQuery = { eventType: 'order.paid', page: 1, size: 20 }
    const data: PageResponse<EventSummary> = { items: [eventSummary], page: 1, size: 20, total: 1 }
    vi.mocked(api.listEvents).mockResolvedValue(data)
    const { client, wrapper } = createWrapper()

    const { result } = renderHook(() => useEvents(query), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.listEvents).toHaveBeenCalledWith(query)
    expect(result.current.data).toBe(data)
    expect(client.getQueryCache().find({ queryKey: queryKeys.events(query) })).toBeDefined()
  })

  it('useDeliveries keeps the previous page while a new filter loads', async () => {
    const firstQuery: DeliveryQuery = { page: 0, size: 20 }
    const secondQuery: DeliveryQuery = { status: 'DEAD', page: 0, size: 20 }
    const first: PageResponse<Delivery> = { items: [delivery], page: 0, size: 20, total: 1 }
    const second: PageResponse<Delivery> = { items: [], page: 0, size: 20, total: 0 }
    vi.mocked(api.listDeliveries)
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce(second)
    const { wrapper } = createWrapper()

    const { result, rerender } = renderHook(({ query }) => useDeliveries(query), {
      wrapper,
      initialProps: { query: firstQuery },
    })
    await waitFor(() => expect(result.current.data).toBe(first))

    rerender({ query: secondQuery })

    // `placeholderData: keepPreviousData` — no empty-table flash while the next poll lands.
    expect(result.current.data).toBe(first)
    expect(result.current.isPlaceholderData).toBe(true)
    await waitFor(() => expect(result.current.data).toBe(second))
  })

  it('useDeliveryDetail fetches the attempt history by delivery id', async () => {
    const detail: DeliveryDetail = { delivery, attempts: [] }
    vi.mocked(api.getDelivery).mockResolvedValue(detail)
    const { client, wrapper } = createWrapper()

    const { result } = renderHook(() => useDeliveryDetail(11), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getDelivery).toHaveBeenCalledWith(11)
    expect(result.current.data).toBe(detail)
    expect(client.getQueryCache().find({ queryKey: ['delivery-detail', 11] })).toBeDefined()
  })

  it('useEventDetail fetches the payload and its deliveries by event id', async () => {
    const detail: EventDetail = {
      eventId: 1,
      eventType: 'order.paid',
      payload: '{"amount":42}',
      createdAt: '2026-11-04T00:00:00',
      deliveryCount: 1,
      deliveries: [delivery],
    }
    vi.mocked(api.getEvent).mockResolvedValue(detail)
    const { client, wrapper } = createWrapper()

    const { result } = renderHook(() => useEventDetail(1), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getEvent).toHaveBeenCalledWith(1)
    expect(result.current.data).toBe(detail)
    expect(client.getQueryCache().find({ queryKey: ['event-detail', 1] })).toBeDefined()
  })

  it('leaves every id-scoped query disabled until an id is selected', async () => {
    const { wrapper } = createWrapper()

    const deliveries = renderHook(() => useDeliveryDetail(undefined), { wrapper })
    const events = renderHook(() => useEventDetail(undefined), { wrapper })
    const stats = renderHook(() => useTenantStats(undefined, '24h'), { wrapper })
    const keys = renderHook(() => useTenantKeys(undefined), { wrapper })

    await waitFor(() => {
      expect(deliveries.result.current.fetchStatus).toBe('idle')
      expect(events.result.current.fetchStatus).toBe('idle')
      expect(stats.result.current.fetchStatus).toBe('idle')
      expect(keys.result.current.fetchStatus).toBe('idle')
    })
    expect(api.getDelivery).not.toHaveBeenCalled()
    expect(api.getEvent).not.toHaveBeenCalled()
    expect(api.getTenantStats).not.toHaveBeenCalled()
    expect(api.listTenantKeys).not.toHaveBeenCalled()
  })

  it('useEndpoints queries the endpoint list', async () => {
    const query: EndpointQuery = { page: 0, size: 20 }
    const data: PageResponse<Endpoint> = { items: [endpoint], page: 0, size: 20, total: 1 }
    vi.mocked(api.listEndpoints).mockResolvedValue(data)
    const { client, wrapper } = createWrapper()

    const { result } = renderHook(() => useEndpoints(query), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.listEndpoints).toHaveBeenCalledWith(query)
    expect(result.current.data).toBe(data)
    expect(client.getQueryCache().find({ queryKey: queryKeys.endpoints(query) })).toBeDefined()
  })

  it('useTenants queries the tenant list', async () => {
    const query: TenantQuery = { search: 'acme', page: 0, size: 20 }
    const data: PageResponse<Tenant> = { items: [tenant], page: 0, size: 20, total: 1 }
    vi.mocked(api.listTenants).mockResolvedValue(data)
    const { client, wrapper } = createWrapper()

    const { result } = renderHook(() => useTenants(query), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.listTenants).toHaveBeenCalledWith(query)
    expect(result.current.data).toBe(data)
    expect(client.getQueryCache().find({ queryKey: queryKeys.tenants(query) })).toBeDefined()
  })

  it('useTenantStats queries stats for the selected tenant and range', async () => {
    const stats: TenantStats = {
      tenantId: 7,
      range: '24h',
      totalEvents: 10,
      deliveries: 12,
      succeeded: 9,
      dead: 1,
      canceled: 2,
      inFlight: 0,
      successRate: 75,
      avgLatencyMs: 20,
      backlog: 0,
      topFailureReasons: [],
    }
    vi.mocked(api.getTenantStats).mockResolvedValue(stats)
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useTenantStats(7, '24h'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getTenantStats).toHaveBeenCalledWith(7, '24h')
    expect(result.current.data).toBe(stats)
  })

  it('useTenantKeys queries one tenant key list', async () => {
    const keys: ApiKey[] = []
    vi.mocked(api.listTenantKeys).mockResolvedValue(keys)
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useTenantKeys(7), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.listTenantKeys).toHaveBeenCalledWith(7)
    expect(result.current.data).toBe(keys)
  })

  it('useOverview queries the aggregate overview for a range', async () => {
    const overview: Overview = {
      range: '24h',
      totalEvents: 10,
      deliveries: 12,
      succeeded: 9,
      dead: 1,
      canceled: 2,
      inFlight: 0,
      successRate: 75,
      avgLatencyMs: 20,
      backlog: 0,
      activeTenants: 2,
      activeEndpoints: 3,
    }
    vi.mocked(api.getOverview).mockResolvedValue(overview)
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useOverview('24h'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getOverview).toHaveBeenCalledWith('24h')
    expect(result.current.data).toBe(overview)
  })

  it('useTimeseries queries the chart series for its query', async () => {
    const query: TimeseriesQuery = { range: '24h', interval: '5m', tenantId: 7 }
    const series: Timeseries = {
      range: '24h',
      interval: '5m',
      bucketSeconds: 300,
      from: '2026-11-04T00:00:00',
      to: '2026-11-05T00:00:00',
      points: [],
    }
    vi.mocked(api.getTimeseries).mockResolvedValue(series)
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useTimeseries(query), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getTimeseries).toHaveBeenCalledWith(query)
    expect(result.current.data).toBe(series)
  })

  it('useLatency queries the latency histogram for its query', async () => {
    const query: LatencyQuery = { range: '7d', tenantId: 7 }
    const latency: Latency = {
      range: '7d',
      samples: 0,
      p50Ms: null,
      p95Ms: null,
      p99Ms: null,
      buckets: [],
    }
    vi.mocked(api.getLatency).mockResolvedValue(latency)
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useLatency(query), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api.getLatency).toHaveBeenCalledWith(query)
    expect(result.current.data).toBe(latency)
  })

  it('fetchEventDetail is the non-hook read the batch replay uses', async () => {
    const detail: EventDetail = {
      eventId: 1,
      eventType: 'order.paid',
      payload: '{}',
      createdAt: '2026-11-04T00:00:00',
      deliveryCount: 0,
      deliveries: [],
    }
    vi.mocked(api.getEvent).mockResolvedValue(detail)

    await expect(fetchEventDetail(1)).resolves.toBe(detail)
    expect(api.getEvent).toHaveBeenCalledWith(1)
  })
})

describe('mutation hooks', () => {
  it('useCreateEndpoint invalidates the endpoint and tenant lists', async () => {
    const created: EndpointCreated = {
      id: 3,
      url: endpoint.url,
      subscribedTypes: '*',
      maxAttempts: 6,
      maxConcurrency: 0,
      status: 'ACTIVE',
      signingSecret: 'whsec_test',
    }
    vi.mocked(api.createEndpoint).mockResolvedValue(created)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateEndpoint(), { wrapper })
    const body: CreateEndpointRequest = { url: endpoint.url, subscribedTypes: '*' }

    await act(async () => {
      await result.current.mutateAsync(body)
    })

    expect(api.createEndpoint).toHaveBeenCalledWith(body)
    await waitFor(() => expect(result.current.data).toBe(created))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['endpoints'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
  })

  it('useSetEndpointStatus routes pause and resume and refreshes every affected list', async () => {
    vi.mocked(api.pauseEndpoint).mockResolvedValue({ ...endpoint, status: 'PAUSED' })
    vi.mocked(api.resumeEndpoint).mockResolvedValue(endpoint)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useSetEndpointStatus(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ endpointId: 3, action: 'pause' })
    })
    await act(async () => {
      await result.current.mutateAsync({ endpointId: 3, action: 'resume' })
    })

    expect(api.pauseEndpoint).toHaveBeenCalledWith(3)
    expect(api.resumeEndpoint).toHaveBeenCalledWith(3)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['endpoints'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['deliveries'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['events'] })
  })

  it('useDeleteEndpoint refreshes endpoints, deliveries, events and tenants', async () => {
    vi.mocked(api.deleteEndpoint).mockResolvedValue(undefined)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useDeleteEndpoint(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync(3)
    })

    expect(api.deleteEndpoint).toHaveBeenCalledWith(3)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['endpoints'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['deliveries'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['events'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
  })

  it('useTestEndpoint forwards the probe payload without invalidating anything', async () => {
    const probe: EndpointTestResult = {
      endpointId: 3,
      success: true,
      retryable: false,
      statusCode: 200,
      errorClass: null,
      durationMs: 12,
      responsePreview: 'ok',
    }
    vi.mocked(api.testEndpoint).mockResolvedValue(probe)
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useTestEndpoint(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ endpointId: 3, payload: { ping: true } })
    })

    expect(api.testEndpoint).toHaveBeenCalledWith(3, { ping: true })
    await waitFor(() => expect(result.current.data).toBe(probe))
  })

  it('useCreateTenant provisions a tenant and refreshes the tenant and key lists', async () => {
    const provisioned: TenantProvisioned = {
      tenantId: 8,
      name: 'demo-new',
      apiKey: 'hk_test',
      keyPrefix: 'hk_test',
    }
    vi.mocked(api.createTenant).mockResolvedValue(provisioned)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateTenant(), { wrapper })
    const body: ProvisionTenantRequest = { name: 'demo-new', rateLimit: 100 }

    await act(async () => {
      await result.current.mutateAsync(body)
    })

    expect(api.createTenant).toHaveBeenCalledWith(body)
    await waitFor(() => expect(result.current.data).toBe(provisioned))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenant-keys'] })
  })

  it('useIssueTenantKey refreshes the key list, the tenant row and tenant details', async () => {
    const issued: IssuedKey = { tenantId: 7, name: 'rotation', apiKey: 'hk_new', keyPrefix: 'hk_new' }
    vi.mocked(api.issueTenantKey).mockResolvedValue(issued)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useIssueTenantKey(7), { wrapper })

    await act(async () => {
      await result.current.mutateAsync()
    })

    expect(api.issueTenantKey).toHaveBeenCalledWith(7)
    await waitFor(() => expect(result.current.data).toBe(issued))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenant-keys'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenant'] })
  })

  it('useRevokeApiKey refreshes the same three views as issuing a key', async () => {
    vi.mocked(api.revokeApiKey).mockResolvedValue(undefined)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useRevokeApiKey(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync(12)
    })

    expect(api.revokeApiKey).toHaveBeenCalledWith(12)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenant-keys'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenant'] })
  })

  it('useReplayDelivery refreshes the delivery, event and event-detail views', async () => {
    vi.mocked(api.replayDelivery).mockResolvedValue(delivery)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useReplayDelivery(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync(11)
    })

    expect(api.replayDelivery).toHaveBeenCalledWith(11)
    await waitFor(() => expect(result.current.data).toBe(delivery))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['deliveries'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['events'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['event-detail'] })
  })

  it('useBatchReplayDeliveries posts the ids and refreshes the same views', async () => {
    const batch: BatchReplay = { replayed: 2, rejected: 0, results: [] }
    vi.mocked(api.replayDeliveries).mockResolvedValue(batch)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useBatchReplayDeliveries(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync([11, 12])
    })

    expect(api.replayDeliveries).toHaveBeenCalledWith([11, 12])
    await waitFor(() => expect(result.current.data).toBe(batch))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['deliveries'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['events'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['event-detail'] })
  })

  it('useUpdateTenant refreshes the changed tenant detail and the tenant list', async () => {
    const updated: Tenant = { ...tenant, rateLimit: 250 }
    vi.mocked(api.updateTenant).mockResolvedValue(updated)
    const { client, wrapper } = createWrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useUpdateTenant(), { wrapper })
    const body: UpdateTenantRequest = { rateLimit: 250 }

    await act(async () => {
      await result.current.mutateAsync({ tenantId: 7, body })
    })

    expect(api.updateTenant).toHaveBeenCalledWith(7, body)
    await waitFor(() => expect(result.current.data).toBe(updated))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.tenant(7) })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tenants'] })
  })
})
