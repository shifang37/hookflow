import { screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ANONYMOUS, useSession } from '@/api/session'
import { renderWithProviders } from '@/test/render'
import { App } from './App'

vi.mock('@/api/session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/session')>()
  return { ...actual, useSession: vi.fn() }
})

const mockedUseSession = vi.mocked(useSession)

describe('App', () => {
  beforeEach(() => {
    mockedUseSession.mockReturnValue({
      data: ANONYMOUS,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('serves the public login route without the authenticated shell', () => {
    renderWithProviders(
      <MemoryRouter initialEntries={['/login']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument()
    expect(screen.queryByText('事件流')).not.toBeInTheDocument()
  })
})
