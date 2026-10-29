import { fireEvent, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/render'
import {
  activeEndpoint,
  pausedEndpoint,
  resetEndpointPageMocks,
  withEndpoints,
} from '@/test/endpointFixtures'
import { useDeleteEndpoint } from '@/api/queries'
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

const mockedUseDelete = vi.mocked(useDeleteEndpoint)

describe('EndpointsPage', () => {
  beforeEach(resetEndpointPageMocks)

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
})
