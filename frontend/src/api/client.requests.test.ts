import axios, { type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCredentials, setAdminToken, setTenantToken } from './auth'
import { ApiError, api, http } from './client'

/**
 * Request-level tests: what each `api.*` function actually puts on the wire.
 *
 * The page tests mock the hooks, and `client.test.ts` covers the pure helpers, so before
 * this file nothing asserted the request functions — a wrong path or a leaked
 * `?status=undefined` would have reached the backend unnoticed. The adapter records the
 * config axios built, and `axios.getUri` runs the same params serializer the real adapters
 * do, so these are the bytes that would have been sent.
 */
interface WireCall {
  method: string
  uri: string
  body: unknown
  headers: Record<string, string>
}

let calls: WireCall[] = []

const okAdapter: AxiosAdapter = async (config) => ({
  data: null,
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
})

function record(config: InternalAxiosRequestConfig): WireCall {
  return {
    method: (config.method ?? 'get').toUpperCase(),
    uri: axios.getUri(config),
    body: config.data,
    headers: config.headers.toJSON() as Record<string, string>,
  }
}

beforeEach(() => {
  calls = []
  clearCredentials()
  setTenantToken('hk_test_secret')
  setAdminToken('admin-token')
  http.defaults.adapter = vi.fn(async (config) => {
    calls.push(record(config))
    return okAdapter(config)
  })
})

/** Body of a write, parsed back from the JSON string axios produced. */
function bodyOf(call: WireCall | undefined): unknown {
  return typeof call?.body === 'string' ? JSON.parse(call.body) : call?.body
}

describe('api request functions', () => {
  it('hits the documented method and path for every call', async () => {
    const cases: Array<{ name: string; call: () => Promise<unknown>; method: string; uri: string }> = [
      {
        name: 'listEvents',
        call: () => api.listEvents({ eventType: 'order.paid', page: 0, size: 20 }),
        method: 'GET',
        uri: '/v1/events?eventType=order.paid&page=0&size=20',
      },
      {
        name: 'listDeliveries',
        call: () => api.listDeliveries({ status: 'DEAD', endpointId: 2, page: 1, size: 20 }),
        method: 'GET',
        uri: '/v1/deliveries?status=DEAD&endpointId=2&page=1&size=20',
      },
      {
        name: 'getDelivery',
        call: () => api.getDelivery(7),
        method: 'GET',
        uri: '/v1/deliveries/7',
      },
      {
        name: 'replayDelivery',
        call: () => api.replayDelivery(7),
        method: 'POST',
        uri: '/v1/deliveries/7/replay',
      },
      {
        name: 'replayDeliveries',
        call: () => api.replayDeliveries([1, 2]),
        method: 'POST',
        uri: '/v1/deliveries/replay',
      },
      {
        name: 'getEvent',
        call: () => api.getEvent(12),
        method: 'GET',
        uri: '/v1/events/12',
      },
      {
        name: 'listEndpoints',
        call: () => api.listEndpoints({ page: 0, size: 20 }),
        method: 'GET',
        uri: '/v1/endpoints?page=0&size=20',
      },
      {
        name: 'createEndpoint',
        call: () => api.createEndpoint({ url: 'https://a.io/hook', maxAttempts: 8 }),
        method: 'POST',
        uri: '/v1/endpoints',
      },
      {
        name: 'pauseEndpoint',
        call: () => api.pauseEndpoint(3),
        method: 'POST',
        uri: '/v1/endpoints/3/pause',
      },
      {
        name: 'resumeEndpoint',
        call: () => api.resumeEndpoint(3),
        method: 'POST',
        uri: '/v1/endpoints/3/resume',
      },
      {
        name: 'testEndpoint',
        call: () => api.testEndpoint(3, { orderId: 123 }),
        method: 'POST',
        uri: '/v1/endpoints/3/test',
      },
      {
        name: 'deleteEndpoint',
        call: () => api.deleteEndpoint(3),
        method: 'DELETE',
        uri: '/v1/endpoints/3',
      },
      {
        name: 'listTenants',
        call: () => api.listTenants({ search: 'acme', page: 0, size: 20 }),
        method: 'GET',
        uri: '/v1/admin/tenants?search=acme&page=0&size=20',
      },
      {
        name: 'createTenant',
        call: () => api.createTenant({ name: 'acme', rateLimit: 100 }),
        method: 'POST',
        uri: '/v1/admin/tenants',
      },
      {
        name: 'getTenant',
        call: () => api.getTenant(1),
        method: 'GET',
        uri: '/v1/admin/tenants/1',
      },
      {
        name: 'getTenantStats',
        call: () => api.getTenantStats(1, '7d'),
        method: 'GET',
        uri: '/v1/admin/tenants/1/stats?range=7d',
      },
      {
        name: 'updateTenant',
        call: () => api.updateTenant(1, { rateLimit: 10 }),
        method: 'PATCH',
        uri: '/v1/admin/tenants/1',
      },
      {
        name: 'listTenantKeys',
        call: () => api.listTenantKeys(1),
        method: 'GET',
        uri: '/v1/admin/tenants/1/keys',
      },
      {
        name: 'issueTenantKey',
        call: () => api.issueTenantKey(1),
        method: 'POST',
        uri: '/v1/admin/tenants/1/keys',
      },
      {
        name: 'revokeApiKey',
        call: () => api.revokeApiKey(11),
        method: 'POST',
        uri: '/v1/admin/keys/11/revoke',
      },
      {
        name: 'getOverview',
        call: () => api.getOverview('24h'),
        method: 'GET',
        uri: '/v1/admin/overview?range=24h',
      },
      {
        name: 'getTimeseries',
        call: () => api.getTimeseries({ range: '24h', interval: '15m' }),
        method: 'GET',
        uri: '/v1/admin/metrics/timeseries?range=24h&interval=15m',
      },
      {
        name: 'getLatency',
        call: () => api.getLatency({ range: '24h' }),
        method: 'GET',
        uri: '/v1/admin/metrics/latency?range=24h',
      },
    ]

    for (const testCase of cases) {
      calls = []
      await testCase.call()

      expect(calls, testCase.name).toHaveLength(1)
      expect(calls[0]?.method, testCase.name).toBe(testCase.method)
      expect(calls[0]?.uri, testCase.name).toBe(testCase.uri)
    }
  })

  it('drops undefined filters instead of sending `?status=` or `?eventId=`', async () => {
    // The console builds its query objects from optional filters, so an unset filter is a
    // present key with an undefined value. axios is what drops it; this is the assertion
    // that would fail if the API layer ever started sending `params: { ...query, status:
    // query.status ?? '' }` instead.
    await api.listEvents({ eventType: undefined, page: 0 })
    await api.listDeliveries({ status: undefined, eventId: undefined })

    expect(calls[0]?.uri).toBe('/v1/events?page=0')
    expect(calls[1]?.uri).toBe('/v1/deliveries')
  })

  it('sends the tenant bearer token to /v1 and the bootstrap token to /v1/admin', async () => {
    await api.listEvents()
    await api.listTenants()

    expect(calls[0]?.headers.Authorization).toBe('Bearer hk_test_secret')
    expect(calls[0]?.headers['X-Bootstrap-Token']).toBeUndefined()

    expect(calls[1]?.headers['X-Bootstrap-Token']).toBe('admin-token')
    expect(calls[1]?.headers.Authorization).toBeUndefined()
  })

  it('serializes write bodies as JSON, with the method the backend expects', async () => {
    await api.createEndpoint({ url: 'https://a.io/hook', maxAttempts: 8, maxConcurrency: 0 })
    await api.replayDeliveries([1, 2])
    await api.updateTenant(1, { weight: 3 })
    await api.testEndpoint(3, { orderId: 123 })

    expect(bodyOf(calls[0])).toEqual({
      url: 'https://a.io/hook',
      maxAttempts: 8,
      maxConcurrency: 0,
    })
    expect(bodyOf(calls[1])).toEqual({ deliveryIds: [1, 2] })
    expect(bodyOf(calls[2])).toEqual({ weight: 3 })
    expect(calls[2]?.method).toBe('PATCH')
    // The probe payload is the request body itself; the backend forwards it verbatim.
    expect(bodyOf(calls[3])).toEqual({ orderId: 123 })
  })

  it('fails before the request goes out when the credential is missing', async () => {
    clearCredentials()

    await expect(api.listEvents()).rejects.toBeInstanceOf(ApiError)
    await expect(api.listTenants()).rejects.toBeInstanceOf(ApiError)
    // Not a failed request: nothing reached the adapter, so there is no half-sent call.
    expect(calls).toHaveLength(0)
  })

  it('turns an error response into an ApiError that keeps code and requestId', async () => {
    http.defaults.adapter = vi.fn(async () => {
      throw {
        isAxiosError: true,
        response: {
          status: 409,
          data: {
            code: 40904,
            error: 'ALREADY_EXISTS',
            message: 'an endpoint with this url already exists',
            requestId: 'req-7',
          },
        },
      }
    })

    const error = await api.createEndpoint({ url: 'https://a.io/hook' }).catch((cause) => cause)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.code).toBe(40904)
    expect(error.errorName).toBe('ALREADY_EXISTS')
    expect(error.requestId).toBe('req-7')
    expect(error.isClientError).toBe(true)
  })

  it('resolves a 204 without a body instead of treating it as a failure', async () => {
    http.defaults.adapter = vi.fn(async (config) => ({
      data: '',
      status: 204,
      statusText: 'No Content',
      headers: {},
      config,
    }))

    await expect(api.deleteEndpoint(3)).resolves.toBe('')
  })
})
