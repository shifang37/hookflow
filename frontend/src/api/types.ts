/**
 * Wire types.
 *
 * These mirror the backend DTOs in `dev.hookflow.api.dto.Dtos` field for field. They
 * are hand-written on purpose: generating them from the OpenAPI document would add a
 * codegen step to every backend change, and the four pages here touch a small, stable
 * subset. If this grows past a handful of endpoints, switch to generated types.
 *
 * Timestamps arrive as naive local date-times that the backend serialises in UTC
 * (`spring.jackson.time-zone: UTC`). Use `parseTimestamp` from `./time`, never
 * `new Date(value)` directly, or a UTC timestamp renders as if it were local.
 */

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** One row of the event stream: an event plus the roll-up of its deliveries. */
export interface EventSummary {
  eventId: number
  eventType: string
  deliveryCount: number
  succeededCount: number
  failedCount: number
  inFlightCount: number
  createdAt: string
}

export const DELIVERY_STATUSES = [
  'PENDING',
  'DELIVERING',
  'RETRY_WAIT',
  'SUCCEEDED',
  'DEAD',
  'CANCELED',
] as const

export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number]

export interface Delivery {
  id: number
  eventId: number
  endpointId: number
  status: DeliveryStatus
  attemptCount: number
  maxAttempts: number
  nextAttemptAt: string | null
  lastErrorClass: string | null
  lastStatusCode: number | null
  createdAt: string
  updatedAt: string
}

/**
 * One event with its payload and the deliveries it fanned out to.
 *
 * `payload` is the raw JSON **text** the platform stored — the API never re-encodes it, so
 * the UI has to parse it to pretty-print it, and falls back to showing the text verbatim if
 * it cannot. `deliveryCount` is the true total; `deliveries` is capped by the backend (200),
 * so a shorter list means "truncated", not "this is everything".
 */
export interface EventDetail {
  eventId: number
  eventType: string
  payload: string
  createdAt: string
  deliveryCount: number
  deliveries: Delivery[]
}

/** One line of a batch replay: either replayed (with its new status) or rejected with a reason. */
export interface ReplayResult {
  deliveryId: number
  replayed: boolean
  status: string | null
  code: number | null
  message: string | null
}

/** Partial success by design: `replayed + rejected === results.length`. */
export interface BatchReplay {
  replayed: number
  rejected: number
  results: ReplayResult[]
}

/** Server-side cap for one batch replay (`DeliveryService.MAX_BATCH_REPLAY`). */
export const MAX_BATCH_REPLAY = 100

export const ENDPOINT_STATUSES = ['ACTIVE', 'PAUSED'] as const
export type EndpointStatus = (typeof ENDPOINT_STATUSES)[number]

/** A customer callback target. The signing secret is not part of this shape — see below. */
export interface Endpoint {
  id: number
  url: string
  /** Comma-separated event types, exact match, or `*` for all. */
  subscribedTypes: string
  maxAttempts: number
  /** 0 = no endpoint-level cap; the tenant budget is the only fence. */
  maxConcurrency: number
  status: EndpointStatus
  createdAt: string
}

/**
 * The create response carries `signingSecret` **once**. It is never returned again by
 * any endpoint, so the UI must show it immediately and say so — a user who closes the
 * dialog without copying it has to create a new endpoint.
 */
export interface EndpointCreated {
  id: number
  url: string
  subscribedTypes: string
  maxAttempts: number
  maxConcurrency: number
  status: EndpointStatus
  signingSecret: string
}

export interface CreateEndpointRequest {
  url: string
  subscribedTypes?: string
  maxAttempts?: number
  maxConcurrency?: number
}

/** `*` means "every event type"; anything else is an exact, comma-separated list. */
export const WILDCARD_SUBSCRIPTION = '*'

export type ApiKeyStatus = 'ACTIVE' | 'REVOKED'

/**
 * Key metadata. There is no secret field and there never can be one: the plaintext
 * exists only in {@link IssuedKey}, at the moment it is created.
 */
export interface ApiKey {
  id: number
  keyPrefix: string
  scopes: string
  status: ApiKeyStatus
  createdAt: string
  revokedAt: string | null
}

/** The one-time answer to "issue a key": the only place the plaintext ever appears. */
export interface IssuedKey {
  tenantId: number
  name: string
  apiKey: string
  keyPrefix: string
}

export type TenantStatus = 'ACTIVE' | 'SUSPENDED'

export interface ProvisionTenantRequest {
  name: string
  rateLimit?: number
  maxInFlight?: number
  weight?: number
}

/**
 * The answer to provisioning a tenant.
 *
 * <p>{@code apiKey} is the first key's plaintext and exists only in this response — the
 * row stores SHA-256, so there is no second chance to read it. The console shows it in
 * the same one-time dialog it uses for a rotated key.
 */
export interface TenantProvisioned {
  tenantId: number
  name: string
  apiKey: string
  keyPrefix: string
}

export interface Tenant {
  tenantId: number
  name: string
  status: TenantStatus
  rateLimit: number
  maxInFlight: number
  weight: number
  endpointCount: number
  activeKeyCount: number
  createdAt: string
}

export interface FailureReason {
  errorClass: string | null
  statusCode: number | null
  count: number
  percentage: number
}

/** Statistics over a fixed window. `range` is echoed back so the UI can label it. */
export interface TenantStats {
  tenantId: number
  range: StatsRange
  totalEvents: number
  deliveries: number
  succeeded: number
  dead: number
  canceled: number
  inFlight: number
  successRate: number
  avgLatencyMs: number | null
  backlog: number
  topFailureReasons: FailureReason[]
}

export interface Overview {
  range: StatsRange
  totalEvents: number
  deliveries: number
  succeeded: number
  dead: number
  canceled: number
  inFlight: number
  successRate: number
  avgLatencyMs: number | null
  backlog: number
  activeTenants: number
  activeEndpoints: number
}

export interface TimeseriesPoint {
  bucketStart: string
  events: number
  attempts: number
  succeeded: number
  dead: number
  canceled: number
}

export interface Timeseries {
  range: StatsRange
  interval: Interval
  bucketSeconds: number
  from: string
  to: string
  points: TimeseriesPoint[]
}

export interface LatencyBucket {
  /** Exclusive upper bound in ms; null is the open-ended "slower than the last edge" bucket. */
  upperBoundMs: number | null
  count: number
  percentage: number
}

export interface Latency {
  range: StatsRange
  samples: number
  p50Ms: number | null
  p95Ms: number | null
  p99Ms: number | null
  buckets: LatencyBucket[]
}

export const STATS_RANGES = ['24h', '7d', '30d', '90d'] as const
export type StatsRange = (typeof STATS_RANGES)[number]

/** The finest offered interval is 5m; the backend rejects anything it cannot bucket. */
export const INTERVALS = ['auto', '5m', '15m', '30m', '1h', '3h', '6h', '12h', '1d'] as const
export type Interval = (typeof INTERVALS)[number]

export interface UpdateTenantRequest {
  rateLimit?: number
  maxInFlight?: number
  weight?: number
}
