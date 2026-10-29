import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { AppShell } from './AppShell'
import { renderWithProviders } from '@/test/render'
import { useUiStore } from '@/stores/uiStore'

function renderShell(initialPath = '/events') {
  return renderWithProviders(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/events" element={<div>page-events</div>} />
          <Route path="/tenants" element={<div>page-tenants</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AppShell', () => {
  beforeEach(() => {
    useUiStore.setState({ tenantToken: 'hk_test', adminToken: 'admin', settingsOpen: false })
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

  it('opens the credential drawer from the header', () => {
    renderShell()

    fireEvent.click(screen.getByRole('button', { name: /凭证/ }))

    expect(useUiStore.getState().settingsOpen).toBe(true)
    // Both trust domains, side by side, because mixing them up is the likely mistake.
    expect(screen.getByPlaceholderText('hk_xxxxxxxx_yyyy…')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/hookflow\.admin\.bootstrap-token/)).toBeInTheDocument()
  })

  it('saves a pasted credential into the store and storage', async () => {
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
  }, 20_000)
})
