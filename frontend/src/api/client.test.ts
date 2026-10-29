import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError, buildHeaders, toApiError } from './client'
import { clearCredentials, setAdminToken, setTenantToken } from './auth'

/** Mirrors what axios actually puts on the wire, without opening a socket. */
function axiosErrorWithBody(status: number, body: unknown): unknown {
  return { isAxiosError: true, response: { status, data: body } }
}

describe('buildHeaders', () => {
  beforeEach(() => {
    clearCredentials()
  })

  it('refuses to send a tenant request without a key, with an actionable message', () => {
    expect(() => buildHeaders('tenant')).toThrowError(ApiError)
    try {
      buildHeaders('tenant')
    } catch (error) {
      expect((error as ApiError).status).toBe(401)
      expect((error as ApiError).message).toContain('租户 API Key')
    }
  })

  it('sends the tenant key as a bearer token', () => {
    setTenantToken('hk_abc_secret')
    expect(buildHeaders('tenant')).toEqual({ Authorization: 'Bearer hk_abc_secret' })
  })

  it('sends the operator token in X-Bootstrap-Token, not as a bearer', () => {
    setAdminToken('bootstrap-token')
    expect(buildHeaders('admin')).toEqual({ 'X-Bootstrap-Token': 'bootstrap-token' })
  })
})

describe('toApiError', () => {
  it('flattens the backend error body, including the requestId support needs', () => {
    const error = toApiError(
      axiosErrorWithBody(409, {
        code: 40901,
        error: 'IDEMPOTENCY_KEY_REUSED',
        message: 'Idempotency key already used with a different payload',
        requestId: '3f2a1b9c',
      }),
    )

    expect(error.status).toBe(409)
    expect(error.code).toBe(40901)
    expect(error.errorName).toBe('IDEMPOTENCY_KEY_REUSED')
    expect(error.requestId).toBe('3f2a1b9c')
    expect(error.message).toMatch(/Idempotency key/)
    expect(error.isClientError).toBe(true)
  })

  it('reports an unreachable backend as status 0 instead of "undefined is not a function"', () => {
    const error = toApiError({ isAxiosError: true })
    expect(error.status).toBe(0)
    expect(error.isClientError).toBe(false)
    expect(error.message).toContain('无法连接后端')
  })

  it('falls back to the HTTP status when the body is not ours', () => {
    const error = toApiError(axiosErrorWithBody(502, 'upstream said no'))
    expect(error.status).toBe(502)
    expect(error.message).toBe('HTTP 502')
  })

  it('passes an ApiError through unchanged', () => {
    const original = new ApiError('boom', { status: 500 })
    expect(toApiError(original)).toBe(original)
  })

  it('treats a 5xx as retryable and a 4xx as not', () => {
    expect(toApiError(axiosErrorWithBody(503, {})).isClientError).toBe(false)
    expect(toApiError(axiosErrorWithBody(400, {})).isClientError).toBe(true)
  })
})
