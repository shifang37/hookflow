import axios, { type AxiosRequestConfig } from 'axios'
import { getAdminToken, getTenantToken } from './auth'
import type {
  ApiKey,
  CreateEndpointRequest,
  Delivery,
  DeliveryStatus,
  Endpoint,
  EndpointCreated,
  Interval,
  IssuedKey,
  Latency,
  Overview,
  PageResponse,
  EventSummary,
  ProvisionTenantRequest,
  StatsRange,
  Tenant,
  TenantProvisioned,
  TenantStats,
  Timeseries,
  UpdateTenantRequest,
} from './types'

/** Which credential a call needs. Not a role check: the backend does that. */
export type Scope = 'tenant' | 'admin'

/**
 * One error type for the whole app.
 *
 * The backend already has a uniform error body (`code`/`error`/`message`/`requestId`),
 * so flattening it into one class means a page renders `error.message` and, when it
 * matters, quotes the `requestId` — which is the only handle support has on a 500.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: number | undefined
  readonly errorName: string | undefined
  readonly requestId: string | undefined

  constructor(
    message: string,
    details: {
      status: number
      code?: number | undefined
      errorName?: string | undefined
      requestId?: string | undefined
      cause?: unknown
    },
  ) {
    super(message, { cause: details.cause })
    this.name = 'ApiError'
    this.status = details.status
    this.code = details.code
    this.errorName = details.errorName
    this.requestId = details.requestId
  }

  /** 4xx that retrying cannot fix: bad input, missing credential, no such row. */
  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500
  }
}

/** Pure so it can be unit-tested without a server, and shared by every call. */
export function buildHeaders(scope: Scope): Record<string, string> {
  if (scope === 'admin') {
    const token = getAdminToken()
    if (token === '') {
      throw new ApiError('缺少运营令牌，请在右上角「凭证」里填入 X-Bootstrap-Token', { status: 401 })
    }
    return { 'X-Bootstrap-Token': token }
  }

  const token = getTenantToken()
  if (token === '') {
    throw new ApiError('缺少租户 API Key，请在右上角「凭证」里填入 hk_ 开头的令牌', { status: 401 })
  }
  return { Authorization: `Bearer ${token}` }
}

/** Pure: maps anything axios throws onto {@link ApiError}. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (axios.isAxiosError(error)) {
    const response = error.response
    if (response === undefined) {
      return new ApiError('无法连接后端（请确认 8080 端口在运行）', {
        status: 0,
        cause: error,
      })
    }
    const body = response.data as
      | { code?: number; error?: string; message?: string; requestId?: string }
      | undefined
    return new ApiError(body?.message ?? `HTTP ${response.status}`, {
      status: response.status,
      code: body?.code,
      errorName: body?.error,
      requestId: body?.requestId,
      cause: error,
    })
  }
  return new ApiError(error instanceof Error ? error.message : '未知错误', { status: 0, cause: error })
}

const http = axios.create({
  // Same origin in dev (Vite proxy) and in production (served from /ui/ by the app),
  // so there is no base URL to configure and no CORS to arrange.
  baseURL: '/',
  timeout: 15_000,
  headers: { Accept: 'application/json' },
})

async function request<T>(scope: Scope, config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request<T>({ ...config, headers: { ...config.headers, ...buildHeaders(scope) } })
    return response.data
  } catch (error) {
    throw toApiError(error)
  }
}

export interface EventQuery {
  eventType?: string | undefined
  page?: number
  size?: number
}

export interface DeliveryQuery {
  eventId?: number | undefined
  endpointId?: number | undefined
  status?: DeliveryStatus | undefined
  page?: number
  size?: number
}

export interface TenantQuery {
  search?: string | undefined
  page?: number
  size?: number
}

export interface EndpointQuery {
  page?: number
  size?: number
}

export interface TimeseriesQuery {
  range: StatsRange
  interval: Interval
  tenantId?: number | undefined
}

export interface LatencyQuery {
  range: StatsRange
  tenantId?: number | undefined
}

export const api = {
  // ---------------------------------------------------------------- tenant domain

  listEvents: (query: EventQuery = {}) =>
    request<PageResponse<EventSummary>>('tenant', { method: 'GET', url: '/v1/events', params: query }),

  listDeliveries: (query: DeliveryQuery = {}) =>
    request<PageResponse<Delivery>>('tenant', { method: 'GET', url: '/v1/deliveries', params: query }),

  replayDelivery: (deliveryId: number) =>
    request<Delivery>('tenant', { method: 'POST', url: `/v1/deliveries/${deliveryId}/replay` }),

  listEndpoints: (query: EndpointQuery = {}) =>
    request<PageResponse<Endpoint>>('tenant', { method: 'GET', url: '/v1/endpoints', params: query }),

  /** The response's `signingSecret` is the only time the plaintext secret exists. */
  createEndpoint: (body: CreateEndpointRequest) =>
    request<EndpointCreated>('tenant', { method: 'POST', url: '/v1/endpoints', data: body }),

  pauseEndpoint: (endpointId: number) =>
    request<Endpoint>('tenant', { method: 'POST', url: `/v1/endpoints/${endpointId}/pause` }),

  resumeEndpoint: (endpointId: number) =>
    request<Endpoint>('tenant', { method: 'POST', url: `/v1/endpoints/${endpointId}/resume` }),

  /**
   * Soft-deletes: queued deliveries become CANCELED, the endpoint disappears from every
   * read and its URL can be registered again. Answers 204 with no body, and 404 if it is
   * already gone.
   */
  deleteEndpoint: (endpointId: number) =>
    request<void>('tenant', { method: 'DELETE', url: `/v1/endpoints/${endpointId}` }),

  // ----------------------------------------------------------------- admin domain

  listTenants: (query: TenantQuery = {}) =>
    request<PageResponse<Tenant>>('admin', { method: 'GET', url: '/v1/admin/tenants', params: query }),

  /** Provisions a tenant and its first key; the response carries the only plaintext copy. */
  createTenant: (body: ProvisionTenantRequest) =>
    request<TenantProvisioned>('admin', { method: 'POST', url: '/v1/admin/tenants', data: body }),

  getTenant: (tenantId: number) =>
    request<Tenant>('admin', { method: 'GET', url: `/v1/admin/tenants/${tenantId}` }),

  getTenantStats: (tenantId: number, range: StatsRange) =>
    request<TenantStats>('admin', {
      method: 'GET',
      url: `/v1/admin/tenants/${tenantId}/stats`,
      params: { range },
    }),

  updateTenant: (tenantId: number, body: UpdateTenantRequest) =>
    request<Tenant>('admin', { method: 'PATCH', url: `/v1/admin/tenants/${tenantId}`, data: body }),

  /** Metadata only — the plaintext key is never readable after creation. */
  listTenantKeys: (tenantId: number) =>
    request<ApiKey[]>('admin', { method: 'GET', url: `/v1/admin/tenants/${tenantId}/keys` }),

  /** The response carries the only copy of the new key's plaintext. */
  issueTenantKey: (tenantId: number) =>
    request<IssuedKey>('admin', { method: 'POST', url: `/v1/admin/tenants/${tenantId}/keys` }),

  /** 204: the key row flips to REVOKED and every request using it starts failing with 401. */
  revokeApiKey: (apiKeyId: number) =>
    request<void>('admin', { method: 'POST', url: `/v1/admin/keys/${apiKeyId}/revoke` }),

  getOverview: (range: StatsRange) =>
    request<Overview>('admin', { method: 'GET', url: '/v1/admin/overview', params: { range } }),

  getTimeseries: (query: TimeseriesQuery) =>
    request<Timeseries>('admin', {
      method: 'GET',
      url: '/v1/admin/metrics/timeseries',
      params: query,
    }),

  getLatency: (query: LatencyQuery) =>
    request<Latency>('admin', { method: 'GET', url: '/v1/admin/metrics/latency', params: query }),
}
