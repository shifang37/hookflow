import axios, { type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ANONYMOUS, fetchSession, login, logout } from './session'
import { http } from './client'

interface WireCall {
  method: string
  uri: string
  body: unknown
  headers: Record<string, string>
}

let calls: WireCall[] = []
let responses: Array<{ data: unknown; status: number }> = []

const adapter: AxiosAdapter = async (config) => {
  calls.push(record(config))
  const next = responses.shift() ?? { data: null, status: 200 }
  return {
    data: next.data,
    status: next.status,
    statusText: 'OK',
    headers: {},
    config,
  }
}

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
  responses = []
  http.defaults.adapter = vi.fn(adapter)
})

describe('session API', () => {
  it('reads the session without installing a client-side identity', async () => {
    responses.push({ data: ANONYMOUS, status: 200 })

    await expect(fetchSession()).resolves.toEqual(ANONYMOUS)
    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe('GET')
    expect(calls[0]?.uri).toBe('/api/auth/session')
  })

  it('logs in with a form post, then re-reads the session for the rotated CSRF token', async () => {
    responses.push(
      { data: ANONYMOUS, status: 200 },
      { data: { authenticated: true }, status: 200 },
      { data: { authenticated: true, username: 'admin', roles: ['ADMIN'] }, status: 200 },
    )

    await expect(login('admin', 'admin123')).resolves.toEqual({
      authenticated: true,
      username: 'admin',
      roles: ['ADMIN'],
    })

    expect(calls.map((call) => `${call.method} ${call.uri}`)).toEqual([
      'GET /api/auth/session',
      'POST /api/auth/login',
      'GET /api/auth/session',
    ])
    expect(calls[1]?.headers['Content-Type']).toContain('application/x-www-form-urlencoded')
    expect(calls[1]?.body).toBe('username=admin&password=admin123')
    // The session itself is a cookie, not a response field the app copies around.
    expect(calls[1]?.headers.Authorization).toBeUndefined()
  })

  it('logs out with a POST to the server-side logout URL', async () => {
    responses.push({ data: '', status: 204 })

    await logout()

    expect(calls).toHaveLength(1)
    expect(calls[0]?.method).toBe('POST')
    expect(calls[0]?.uri).toBe('/api/auth/logout')
  })

  it('maps a failed probe to the shared ApiError instead of leaking axios', async () => {
    http.defaults.adapter = vi.fn(async () => {
      throw {
        isAxiosError: true,
        response: { status: 503, data: { code: 50300, message: 'session store unavailable' } },
      }
    })

    await expect(fetchSession()).rejects.toMatchObject({
      status: 503,
      code: 50300,
      message: 'session store unavailable',
    })
  })

  it('maps a rejected login to the shared ApiError', async () => {
    http.defaults.adapter = vi.fn(async (config) => {
      calls.push(record(config))
      if ((config.method ?? 'get').toUpperCase() === 'POST') {
        throw {
          isAxiosError: true,
          response: { status: 401, data: { code: 40100, message: 'Invalid username or password' } },
        }
      }
      return {
        data: ANONYMOUS,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    })

    await expect(login('admin', 'wrong')).rejects.toMatchObject({ status: 401, code: 40100 })
  })

  it('maps a failed logout to the shared ApiError', async () => {
    http.defaults.adapter = vi.fn(async () => {
      throw { isAxiosError: true, response: { status: 500, data: { message: 'boom' } } }
    })

    await expect(logout()).rejects.toMatchObject({ status: 500, message: 'boom' })
  })
})
