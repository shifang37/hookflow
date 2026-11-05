import { create } from 'zustand'
import { clearCredentials as clearStoredCredentials, getTenantToken, setTenantToken } from '@/api/auth'
import type { Interval, StatsRange } from '@/api/types'

/**
 * UI-only state. Server state lives in React Query, not here — duplicating a fetched
 * list into a store is how two sources of truth start disagreeing.
 *
 * What is here: the watch window shared by the monitoring page, and the tenant
 * credential drawer. The operator identity is *not* here: it belongs to
 * `/api/auth/session` and React Query, because the server owns the session and the
 * client must never be able to make itself an admin by editing local state.
 */
interface UiState {
  range: StatsRange
  interval: Interval
  tenantToken: string
  settingsOpen: boolean

  updateRange: (range: StatsRange) => void
  updateInterval: (interval: Interval) => void
  saveTenantToken: (token: string) => void
  clearCredentials: () => void
  openSettings: () => void
  closeSettings: () => void
}

export const useUiStore = create<UiState>((set) => ({
  range: '24h',
  interval: 'auto',
  tenantToken: getTenantToken(),
  settingsOpen: false,

  updateRange: (range) => set({ range }),
  updateInterval: (interval) => set({ interval }),

  saveTenantToken: (token) => {
    setTenantToken(token)
    // Re-read from storage instead of trusting the argument: `setTenantToken` trims and
    // may no-op on a storage failure, and the store must reflect what a request will
    // actually send.
    set({ tenantToken: getTenantToken() })
  },

  clearCredentials: () => {
    clearStoredCredentials()
    set({ tenantToken: '' })
  },

  openSettings: () => set({ settingsOpen: true }),
  closeSettings: () => set({ settingsOpen: false }),
}))
