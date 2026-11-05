import { beforeEach, describe, expect, it } from 'vitest'
import { useUiStore } from './uiStore'

/**
 * The store is small but the actions are called from different screens; a broken
 * update here shows up as a window that silently resets between pages.
 */
describe('uiStore', () => {
  beforeEach(() => {
    useUiStore.setState({ range: '24h', interval: 'auto', tenantToken: '', settingsOpen: false })
    window.localStorage.clear()
  })

  it('updates the shared watch window', () => {
    useUiStore.getState().updateRange('7d')
    useUiStore.getState().updateInterval('1h')

    expect(useUiStore.getState().range).toBe('7d')
    expect(useUiStore.getState().interval).toBe('1h')
  })

  it('persists a tenant token and reads it back from storage', () => {
    useUiStore.getState().saveTenantToken('  hk_test_secret  ')

    expect(useUiStore.getState().tenantToken).toBe('hk_test_secret')
    expect(window.localStorage.getItem('hookflow.tenant-token')).toBe('hk_test_secret')
  })

  it('clears the tenant token from both the store and storage', () => {
    useUiStore.getState().saveTenantToken('hk_test_secret')
    useUiStore.getState().clearCredentials()

    expect(useUiStore.getState().tenantToken).toBe('')
    expect(window.localStorage.getItem('hookflow.tenant-token')).toBeNull()
  })

  it('opens and closes the credential drawer', () => {
    useUiStore.getState().openSettings()
    expect(useUiStore.getState().settingsOpen).toBe(true)

    useUiStore.getState().closeSettings()
    expect(useUiStore.getState().settingsOpen).toBe(false)
  })
})
