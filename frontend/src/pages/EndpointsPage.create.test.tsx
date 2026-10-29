import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { MutateOptions } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import {
  activeEndpoint,
  createResult,
  createdEndpoint,
  resetEndpointPageMocks,
  withEndpoints,
} from '@/test/endpointFixtures'
import { useCreateEndpoint } from '@/api/queries'
import type { CreateEndpointRequest, EndpointCreated } from '@/api/types'
import { EndpointsPage } from './EndpointsPage'

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>()
  return {
    ...actual,
    useEndpoints: vi.fn(),
    useCreateEndpoint: vi.fn(),
    useSetEndpointStatus: vi.fn(),
    useDeleteEndpoint: vi.fn(),
  }
})

const mockedUseCreate = vi.mocked(useCreateEndpoint)

/** `delay: null` skips userEvent's per-keystroke tick; AntD's Form re-render is the slow part. */
const typing = () => userEvent.setup({ delay: null })

describe('EndpointsPage', () => {
  beforeEach(resetEndpointPageMocks)

  it('creates an endpoint and then shows the secret exactly once', async () => {
    const user = typing()
    // The real mutate signature is (variables, options) with a four-argument onSuccess,
    // so the mock is typed against react-query's own option shape rather than a
    // hand-written one-argument callback.
    const mutate = vi.fn(
      (
        body: CreateEndpointRequest,
        options?: MutateOptions<EndpointCreated, Error, CreateEndpointRequest, unknown>,
      ) => {
        options?.onSuccess?.(createdEndpoint, body, undefined, {} as never)
      },
    )
    mockedUseCreate.mockReturnValue(createResult({ mutate }))
    withEndpoints([])

    renderWithProviders(<EndpointsPage />)
    await user.click(screen.getByRole('button', { name: /新增 Endpoint/ }))

    const urlInput = screen.getByPlaceholderText('https://customer.example/hooks/hookflow')
    await user.type(urlInput, 'https://a.io/h')
    await user.click(screen.getByRole('button', { name: /创\s*建/ }))

    await waitFor(() => expect(mutate).toHaveBeenCalledTimes(1))
    expect(mutate.mock.calls[0]?.[0]).toMatchObject({
      url: 'https://a.io/h',
      maxAttempts: 8,
      maxConcurrency: 0,
      subscribedTypes: '*',
    })

    // The secret dialog is the whole point of the flow, so assert it is what appears.
    expect(await screen.findByText('签名密钥只显示这一次')).toBeInTheDocument()
    expect(screen.getByLabelText('signing secret')).toHaveValue('c2VjcmV0')
  }, 30_000)

  it('rejects a callback URL that is not http(s) before the round trip', async () => {
    const user = typing()
    const mutate = vi.fn()
    mockedUseCreate.mockReturnValue(createResult({ mutate }))
    withEndpoints([activeEndpoint])

    renderWithProviders(<EndpointsPage />)
    await user.click(screen.getByRole('button', { name: /新增 Endpoint/ }))
    await user.type(
      screen.getByPlaceholderText('https://customer.example/hooks/hookflow'),
      'ftp://a.io',
    )
    await user.click(screen.getByRole('button', { name: /创\s*建/ }))

    expect(await screen.findByText('必须是 http:// 或 https:// 开头的完整地址')).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  }, 30_000)
})
