import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  api,
  type DeliveryQuery,
  type EndpointQuery,
  type EventQuery,
  type LatencyQuery,
  type TenantQuery,
  type TimeseriesQuery,
} from './client'
import type { CreateEndpointRequest, ProvisionTenantRequest, StatsRange, UpdateTenantRequest } from './types'

/**
 * Re-exported so a page can import its hook and its parameter type from one module.
 * The definitions still live in `client.ts`, where they describe the wire call.
 */
export type {
  DeliveryQuery,
  EndpointQuery,
  EventQuery,
  LatencyQuery,
  TenantQuery,
  TimeseriesQuery,
} from './client'

/**
 * Polling budgets, in one place.
 *
 * The event stream is the only thing a human watches continuously, so it gets 2s
 * (inside the roadmap's ≤3s budget). Everything else is slower on purpose: a chart
 * that redraws every second costs a bucket query per second per open tab and shows
 * nothing new — the buckets are 15 minutes wide.
 */
export const POLL_INTERVAL_MS = {
  eventStream: 2_000,
  deliveries: 3_000,
  endpoints: 15_000,
  tenants: 10_000,
  overview: 5_000,
  timeseries: 15_000,
  latency: 30_000,
} as const

export const queryKeys = {
  events: (query: EventQuery) => ['events', query] as const,
  eventDetail: (eventId: number) => ['event-detail', eventId] as const,
  deliveries: (query: DeliveryQuery) => ['deliveries', query] as const,
  endpoints: (query: EndpointQuery) => ['endpoints', query] as const,
  tenants: (query: TenantQuery) => ['tenants', query] as const,
  tenant: (tenantId: number) => ['tenant', tenantId] as const,
  tenantKeys: (tenantId: number) => ['tenant-keys', tenantId] as const,
  tenantStats: (tenantId: number, range: StatsRange) => ['tenant-stats', tenantId, range] as const,
  overview: (range: StatsRange) => ['overview', range] as const,
  timeseries: (query: TimeseriesQuery) => ['timeseries', query] as const,
  latency: (query: LatencyQuery) => ['latency', query] as const,
}

/** Drops empty strings so `?eventType=` never reaches the backend as a filter. */
export function blankToUndefined(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === '' ? undefined : value.trim()
}

export function useEvents(query: EventQuery) {
  return useQuery({
    queryKey: queryKeys.events(query),
    queryFn: () => api.listEvents(query),
    refetchInterval: POLL_INTERVAL_MS.eventStream,
    // Keeps the previous page on screen while the next poll lands, so the table does
    // not flash empty every two seconds.
    placeholderData: keepPreviousData,
  })
}

export function useDeliveries(query: DeliveryQuery) {
  return useQuery({
    queryKey: queryKeys.deliveries(query),
    queryFn: () => api.listDeliveries(query),
    refetchInterval: POLL_INTERVAL_MS.deliveries,
    placeholderData: keepPreviousData,
  })
}

/**
 * One event's payload and deliveries, for the event drawer.
 *
 * No polling: the payload is immutable, and the delivery list inside it changes only when
 * something is replayed — which invalidates this key anyway. Polling it would redraw a
 * pretty-printed payload every two seconds for no new information.
 */
export function useEventDetail(eventId: number | undefined) {
  return useQuery({
    queryKey: queryKeys.eventDetail(eventId ?? -1),
    queryFn: () => api.getEvent(eventId as number),
    enabled: eventId !== undefined,
  })
}

/**
 * Non-hook fetch, because the event table's batch action has to resolve delivery ids before
 * it knows what to replay: select events → fetch their details → replay the DEAD deliveries.
 */
export function fetchEventDetail(eventId: number) {
  return api.getEvent(eventId)
}

export function useEndpoints(query: EndpointQuery) {
  return useQuery({
    queryKey: queryKeys.endpoints(query),
    queryFn: () => api.listEndpoints(query),
    refetchInterval: POLL_INTERVAL_MS.endpoints,
    placeholderData: keepPreviousData,
  })
}

export function useCreateEndpoint() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateEndpointRequest) => api.createEndpoint(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['endpoints'] })
      // The tenant table shows `endpointCount`; without this it would lag one poll behind.
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
    },
  })
}

export type EndpointAction = 'pause' | 'resume'

/**
 * Pause and resume share one mutation because they share one consequence: the set of
 * deliveries that will or will not be attempted changes, so both lists have to be
 * invalidated. Pausing cancels queued and retrying deliveries; resuming does not
 * resurrect them (that is the backend's documented rule, not a bug to paper over here).
 */
export function useSetEndpointStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ endpointId, action }: { endpointId: number; action: EndpointAction }) =>
      action === 'pause' ? api.pauseEndpoint(endpointId) : api.resumeEndpoint(endpointId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['endpoints'] })
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['events'] })
    },
  })
}

/**
 * Deletion shares pause's consequences (queued deliveries are cancelled, the delivery and
 * event lists therefore change) and adds two of its own: the row leaves the endpoint list,
 * and the tenant's `endpointCount` goes down. The confirmation lives in the page rather
 * than here — a mutation hook cannot ask, and this one cannot be undone.
 */
export function useDeleteEndpoint() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (endpointId: number) => api.deleteEndpoint(endpointId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['endpoints'] })
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['events'] })
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
    },
  })
}

export function useTenants(query: TenantQuery) {
  return useQuery({
    queryKey: queryKeys.tenants(query),
    queryFn: () => api.listTenants(query),
    refetchInterval: POLL_INTERVAL_MS.tenants,
    placeholderData: keepPreviousData,
  })
}

export function useTenantStats(tenantId: number | undefined, range: StatsRange) {
  return useQuery({
    queryKey: queryKeys.tenantStats(tenantId ?? -1, range),
    queryFn: () => api.getTenantStats(tenantId as number, range),
    enabled: tenantId !== undefined,
    placeholderData: keepPreviousData,
  })
}

/**
 * Provisioning invalidates the tenant list because the new row changes both the page and
 * the total; it does not need to touch `tenant-keys` (the drawer is showing some other
 * tenant, if any) but doing so is cheaper than reasoning about which drawer is open.
 */
export function useCreateTenant() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProvisionTenantRequest) => api.createTenant(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
      void queryClient.invalidateQueries({ queryKey: ['tenant-keys'] })
    },
  })
}

/**
 * A tenant's keys, including revoked ones: a rotation is only auditable if the old key
 * is still visible as revoked, and "it disappeared" would be indistinguishable from
 * "it was never issued".
 */
export function useTenantKeys(tenantId: number | undefined) {
  return useQuery({
    queryKey: queryKeys.tenantKeys(tenantId ?? -1),
    queryFn: () => api.listTenantKeys(tenantId as number),
    enabled: tenantId !== undefined,
    refetchInterval: POLL_INTERVAL_MS.tenants,
  })
}

/**
 * Issuing and revoking both change the tenant row's `activeKeyCount`, so both invalidate
 * the tenant list as well as the key list — otherwise the drawer and the table behind it
 * would disagree for up to one poll interval.
 */
export function useIssueTenantKey(tenantId: number | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.issueTenantKey(tenantId as number),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenant-keys'] })
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
      void queryClient.invalidateQueries({ queryKey: ['tenant'] })
    },
  })
}

/**
 * Revoking does not need the tenant id: the endpoint takes only the key id, and the
 * invalidation below is deliberately broad (`['tenant-keys']`), because the console shows
 * one drawer at a time and a stale count on another row would be the worse outcome.
 */
export function useRevokeApiKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (apiKeyId: number) => api.revokeApiKey(apiKeyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenant-keys'] })
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
      void queryClient.invalidateQueries({ queryKey: ['tenant'] })
    },
  })
}

export function useOverview(range: StatsRange) {
  return useQuery({
    queryKey: queryKeys.overview(range),
    queryFn: () => api.getOverview(range),
    refetchInterval: POLL_INTERVAL_MS.overview,
    placeholderData: keepPreviousData,
  })
}

export function useTimeseries(query: TimeseriesQuery) {
  return useQuery({
    queryKey: queryKeys.timeseries(query),
    queryFn: () => api.getTimeseries(query),
    refetchInterval: POLL_INTERVAL_MS.timeseries,
    placeholderData: keepPreviousData,
  })
}

export function useLatency(query: LatencyQuery) {
  return useQuery({
    queryKey: queryKeys.latency(query),
    queryFn: () => api.getLatency(query),
    refetchInterval: POLL_INTERVAL_MS.latency,
    placeholderData: keepPreviousData,
  })
}

/**
 * Replay is a one-shot action rather than something to poll, so it stays a mutation that
 * invalidates the three views it can change: the delivery it moved, the event counters it
 * moved, and an open event drawer showing that delivery.
 */
export function useReplayDelivery() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (deliveryId: number) => api.replayDelivery(deliveryId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['events'] })
      void queryClient.invalidateQueries({ queryKey: ['event-detail'] })
    },
  })
}

/**
 * Batch replay. Same invalidations as the single-row one, because the same rows move; the
 * caller gets a partial result and is responsible for telling the operator which ids were
 * rejected and why.
 */
export function useBatchReplayDeliveries() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (deliveryIds: number[]) => api.replayDeliveries(deliveryIds),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['events'] })
      void queryClient.invalidateQueries({ queryKey: ['event-detail'] })
    },
  })
}

export function useUpdateTenant() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tenantId, body }: { tenantId: number; body: UpdateTenantRequest }) =>
      api.updateTenant(tenantId, body),
    onSuccess: (tenant) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.tenant(tenant.tenantId) })
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
    },
  })
}
