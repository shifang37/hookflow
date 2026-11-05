import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { ANONYMOUS, login, useSession } from '@/api/session'
import { renderWithProviders } from '@/test/render'
import { LoginPage } from './LoginPage'

vi.mock('@/api/session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/session')>()
  return {
    ...actual,
    login: vi.fn(),
    useSession: vi.fn(),
  }
})

const mockedLogin = vi.mocked(login)
const mockedUseSession = vi.mocked(useSession)

function sessionState(data = ANONYMOUS) {
  return { data, isPending: false, isError: false, refetch: vi.fn() } as unknown as ReturnType<typeof useSession>
}

function renderLogin() {
  return renderWithProviders(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/events" element={<div>page-events</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    mockedLogin.mockReset()
    mockedUseSession.mockReset()
    mockedUseSession.mockReturnValue(sessionState())
  })

  it('posts the credentials and lands on the events page', async () => {
    const user = userEvent.setup()
    mockedLogin.mockResolvedValue({ authenticated: true, username: 'admin', roles: ['ADMIN'] })
    renderLogin()

    await user.type(screen.getByPlaceholderText('admin'), 'admin')
    await user.type(screen.getByPlaceholderText('••••••••'), 'admin123')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    await waitFor(() => expect(mockedLogin).toHaveBeenCalledWith('admin', 'admin123'))
    expect(await screen.findByText('page-events')).toBeInTheDocument()
  })

  it('shows the server error and stays on the form', async () => {
    const user = userEvent.setup()
    mockedLogin.mockRejectedValue(new ApiError('Invalid username or password', { status: 401 }))
    renderLogin()

    await user.type(screen.getByPlaceholderText('admin'), 'admin')
    await user.type(screen.getByPlaceholderText('••••••••'), 'wrong')
    await user.click(screen.getByRole('button', { name: /登\s*录/ }))

    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument()
    expect(screen.queryByText('page-events')).not.toBeInTheDocument()
  })

  it('does not render the form when a session already exists', () => {
    mockedUseSession.mockReturnValue(
      sessionState({ authenticated: true, username: 'admin', roles: ['ADMIN'] }),
    )

    renderLogin()

    expect(screen.getByText('page-events')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /登\s*录/ })).not.toBeInTheDocument()
  })
})
