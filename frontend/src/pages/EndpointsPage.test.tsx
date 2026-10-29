import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { MutateOptions } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'
import type { CreateEndpointRequest, Endpoint, EndpointCreated } from '@/api/types'

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

import { useCreateEndpoint, useDeleteEndpoint, useEndpoints, useSetEndpointStatus } from '@/api/queries'
import { EndpointsPage } from './EndpointsPage'

const mockedUseEndpoints = vi.mocked(useEndpoints)
const mockedUseCreate = vi.mocked(useCreateEndpoint)
const mockedUseStatus = vi.mocked(useSetEndpointStatus)
const mockedUseDelete = vi.mocked(useDeleteEndpoint)

/** `delay: null` skips userEvent's per-keystroke tick; AntD's Form re-render is the slow part. */
const typing = () => userEvent.setup({ delay: null })

const activeEndpoint: Endpoint = {
  id: 1,
  url: 'https://customer.example/hooks/hookflow',
  subscribedTypes: '*',
  maxAttempts: 8,
  maxConcurrency: 0,
  status: 'ACTIVE',
  createdAt: '2026-10-29T08:00:00.000',
}

const pausedEndpoint: Endpoint = {
  id: 2,
  url: 'https://legacy.example/hook',
  subscribedTypes: 'order.paid,order.refunded',
  maxAttempts: 5,
  maxConcurrency: 10,
  status: 'PAUSED',
  createdAt: '2026-10-28T08:00:00.000',
}

function withEndpoints(items: Endpoint[]) {
  mockedUseEndpoints.mockReturnValue({
    data: { items, page: 0, size: 20, total: items.length },
    isFetching: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useEndpoints>)
}

function createResult(
  overrides: Partial<ReturnType<typeof useCreateEndpoint>> = {},
): ReturnType<typeof useCreateEndpoint> {
  return {
    mutate: vi.fn(),
    isPending: false,
    ...overrides,
  } as unknown as ReturnType<typeof useCreateEndpoint>
}

describe('EndpointsPage', () => {
  beforeEach(() => {
    mockedUseEndpoints.mockReset()
    mockedUseCreate.mockReset()
    mockedUseStatus.mockReset()
    mockedUseDelete.mockReset()
    mockedUseCreate.mockReturnValue(createResult())
    mockedUseStatus.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useSetEndpointStatus>)
    mockedUseDelete.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useDeleteEndpoint>)
    useUiStore.setState({ tenantToken: 'hk_test', adminToken: '' })
  })

  it('lists endpoints with subscription, caps and status', () => {
    withEndpoints([activeEndpoint, pausedEndpoint])

    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText('https://customer.example/hooks/hookflow')).toBeInTheDocument()
    expect(screen.getByText('全部事件')).toBeInTheDocument()
    expect(screen.getByText('order.paid')).toBeInTheDocument()
    expect(screen.getByText('order.refunded')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('已暂停')).toBeInTheDocument()
    // 0 means "no endpoint-level cap"; rendering it as "0" would read like a limit.
    expect(screen.getByText('不限')).toBeInTheDocument()
  })

  it('offers pause on active rows and resume on paused ones', () => {
    withEndpoints([activeEndpoint, pausedEndpoint])

    renderWithProviders(<EndpointsPage />)

    // AntD inserts a space between two CJK characters, so the names are not literal.
    expect(screen.getAllByRole('button', { name: /暂\s*停/ })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: /恢\s*复/ })).toHaveLength(1)
  })

  it('warns what pausing cancels before doing it', () => {
    withEndpoints([activeEndpoint])

    renderWithProviders(<EndpointsPage />)
    fireEvent.click(screen.getByRole('button', { name: /暂\s*停/ }))

    expect(screen.getByText('暂停这个 Endpoint？')).toBeInTheDocument()
    expect(screen.getByText(/会立刻取消它下面所有 PENDING \/ RETRY_WAIT 的投递/)).toBeInTheDocument()
  })

  it('says that resuming does not resurrect cancelled deliveries', () => {
    withEndpoints([pausedEndpoint])

    renderWithProviders(<EndpointsPage />)
    fireEvent.click(screen.getByRole('button', { name: /恢\s*复/ }))

    expect(screen.getByText(/已取消的投递不会复活/)).toBeInTheDocument()
  })

  it('warns what deleting cancels, and that it cannot be undone', () => {
    withEndpoints([activeEndpoint])

    renderWithProviders(<EndpointsPage />)
    // The row button is `删 除`; the confirmation's OK button is `确认删除`, so anchor the
    // matcher or it would find both.
    fireEvent.click(screen.getByRole('button', { name: /^删\s*除$/ }))

    expect(screen.getByText('删除这个 Endpoint？')).toBeInTheDocument()
    expect(
      screen.getByText(/会取消它下面所有 PENDING \/ RETRY_WAIT 的投递，且无法恢复/),
    ).toBeInTheDocument()
  })

  it('deletes only after the operator confirms', () => {
    const mutate = vi.fn()
    mockedUseDelete.mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useDeleteEndpoint>)
    withEndpoints([activeEndpoint])

    renderWithProviders(<EndpointsPage />)
    fireEvent.click(screen.getByRole('button', { name: /^删\s*除$/ }))
    expect(mutate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    expect(mutate).toHaveBeenCalledTimes(1)
    expect(mutate.mock.calls[0]?.[0]).toBe(activeEndpoint.id)
  })

  it('creates an endpoint and then shows the secret exactly once', async () => {
    const user = typing()
    const created: EndpointCreated = {
      id: 9,
      url: 'https://a.io/h',
      subscribedTypes: '*',
      maxAttempts: 8,
      maxConcurrency: 0,
      status: 'ACTIVE',
      signingSecret: 'c2VjcmV0',
    }
    // The real mutate signature is (variables, options) with a four-argument onSuccess,
    // so the mock is typed against react-query's own option shape rather than a
    // hand-written one-argument callback.
    const mutate = vi.fn(
      (
        body: CreateEndpointRequest,
        options?: MutateOptions<EndpointCreated, Error, CreateEndpointRequest, unknown>,
      ) => {
        options?.onSuccess?.(created, body, undefined, {} as never)
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
    withEndpoints([])

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

  it('shows the empty state when the tenant has no endpoints', () => {
    withEndpoints([])

    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText(/还没有 Endpoint/)).toBeInTheDocument()
  })

  it('points at the missing credential instead of pretending the list is empty', () => {
    useUiStore.setState({ tenantToken: '' })
    withEndpoints([])

    renderWithProviders(<EndpointsPage />)

    expect(screen.getByText('尚未配置租户 API Key')).toBeInTheDocument()
  })
})
