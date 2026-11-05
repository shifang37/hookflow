import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { logout, type Session } from '@/api/session'
import { AppShell } from './AppShell'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'

vi.mock('@/api/session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/session')>()
  return { ...actual, logout: vi.fn() }
})

const mockedLogout = vi.mocked(logout)

function renderShell(initialPath = '/events', session?: Session) {
  return renderWithProviders(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/login" element={<div>page-login</div>} />
        <Route element={<AppShell />}>
          <Route path="/events" element={<div>page-events</div>} />
          <Route path="/tenants" element={<div>page-tenants</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
    { session },
  )
}

const VIEWER: Session = { authenticated: true, username: 'viewer', roles: ['VIEWER'] }

describe('AppShell', () => {
  beforeEach(() => {
    mockedLogout.mockReset()
    mockedLogout.mockResolvedValue(undefined)
    useUiStore.setState({ tenantToken: 'hk_test', settingsOpen: false })
  })

  it('renders the navigation and the routed page', () => {
    renderShell()

    expect(screen.getByText('事件流')).toBeInTheDocument()
    expect(screen.getByText('投递')).toBeInTheDocument()
    expect(screen.getByText('Endpoints')).toBeInTheDocument()
    expect(screen.getByText('租户')).toBeInTheDocument()
    expect(screen.getByText('系统监控')).toBeInTheDocument()
    expect(screen.getByText('page-events')).toBeInTheDocument()
  })

  it('shows the identity and role from the server session', () => {
    renderShell('/events', VIEWER)

    expect(screen.getByText('viewer')).toBeInTheDocument()
    expect(screen.getByText('只读')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
  })

  it('opens the tenant credential drawer from the header', () => {
    renderShell()

    fireEvent.click(screen.getByRole('button', { name: /凭证/ }))

    expect(useUiStore.getState().settingsOpen).toBe(true)
    // Only the tenant key is entered here. The operator identity is a session and
    // must never be a pasteable field in the browser.
    expect(screen.getByPlaceholderText('hk_xxxxxxxx_yyyy…')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/bootstrap-token/)).not.toBeInTheDocument()
  })

  it('clears the tenant credential from the drawer', async () => {
    const user = userEvent.setup()
    renderShell()
    await user.click(screen.getByRole('button', { name: /凭证/ }))

    await user.click(screen.getByRole('button', { name: /清\s*空/ }))

    expect(useUiStore.getState().tenantToken).toBe('')
    expect(window.localStorage.getItem('hookflow.tenant-token')).toBeNull()
  })

  it('logs out through the server and leaves the shell', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByRole('button', { name: '退出登录' }))

    await waitFor(() => expect(mockedLogout).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('page-login')).toBeInTheDocument()
  })

  it('saves a pasted tenant credential into the store and storage', async () => {
    const user = userEvent.setup()
    renderShell()
    await user.click(screen.getByRole('button', { name: /凭证/ }))

    const tenantInput = screen.getByPlaceholderText('hk_xxxxxxxx_yyyy…')
    // The drawer prefills from the store, so clear before typing.
    await user.clear(tenantInput)
    await user.type(tenantInput, 'hk_pasted_key')
    // AntD inserts a space between two CJK characters (`保存` renders as `保 存`), so the
    // accessible name is not the literal string.
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))

    expect(useUiStore.getState().tenantToken).toBe('hk_pasted_key')
    expect(window.localStorage.getItem('hookflow.tenant-token')).toBe('hk_pasted_key')
    expect(useUiStore.getState().settingsOpen).toBe(false)
    // Typing character by character through AntD's controlled Form re-renders the whole
    // drawer each keystroke, which does not fit the 5s default in jsdom.
  }, 60_000)
})
