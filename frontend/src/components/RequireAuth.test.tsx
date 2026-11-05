import { screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSession, type Session } from '@/api/session'
import { renderWithProviders } from '@/test/render'
import { RequireAuth } from './RequireAuth'

vi.mock('@/api/session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/session')>()
  return { ...actual, useSession: vi.fn() }
})

const mockedUseSession = vi.mocked(useSession)

const ADMIN: Session = { authenticated: true, username: 'admin', roles: ['ADMIN'] }

function renderGate() {
  return renderWithProviders(
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/login" element={<div>login-page</div>} />
        <Route element={<RequireAuth />}>
          <Route path="/protected" element={<div>protected-page</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  beforeEach(() => {
    mockedUseSession.mockReset()
  })

  it('renders the protected outlet for an authenticated session', () => {
    mockedUseSession.mockReturnValue({
      data: ADMIN,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    renderGate()

    expect(screen.getByText('protected-page')).toBeInTheDocument()
  })

  it('redirects an anonymous visitor to the login page', () => {
    mockedUseSession.mockReturnValue({
      data: { authenticated: false, username: null, roles: [] },
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    renderGate()

    expect(screen.getByText('login-page')).toBeInTheDocument()
  })

  it('shows a retryable error instead of pretending the user is logged out', () => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    renderGate()

    expect(screen.getByText('无法确认登录状态')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })

  it('does not render the outlet while the session probe is pending', () => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    renderGate()

    expect(screen.queryByText('protected-page')).not.toBeInTheDocument()
  })
})
