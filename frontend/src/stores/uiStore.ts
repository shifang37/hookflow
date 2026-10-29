import { create } from 'zustand'
import {
  clearCredentials as clearStoredCredentials,
  getAdminToken,
  getTenantToken,
  setAdminToken,
  setTenantToken,
} from '@/api/auth'
import type { Interval, StatsRange } from '@/api/types'

/**
 * UI-only state. Server state lives in React Query, not here — duplicating a fetched
 * list into a store is how two sources of truth start disagreeing.
 *
 * What is here: the watch window shared by the monitoring page, and the credential
 * drawer. The drawer lives in the store rather than in `AppShell`'s props because a
 * page that notices a missing credential needs to open it, and threading a callback
 * through three levels to do that is how a skeleton grows a prop-drilling problem.
 */
interface UiState {
  range: StatsRange
  interval: Interval
  tenantToken: string
  adminToken: string
  settingsOpen: boolean

  updateRange: (range: StatsRange) => void
  updateInterval: (interval: Interval) => void
  saveCredentials: (next: { tenantToken?: string | undefined; adminToken?: string | undefined }) => void
  clearCredentials: () => void
  openSettings: () => void
  closeSettings: () => void
}

export const useUiStore = create<UiState>((set) => ({
  range: '24h',
  interval: 'auto',
  tenantToken: getTenantToken(),
  adminToken: getAdminToken(),
  settingsOpen: false,

  updateRange: (range) => set({ range }),
  updateInterval: (interval) => set({ interval }),

  saveCredentials: (next) => {
    if (next.tenantToken !== undefined) {
      setTenantToken(next.tenantToken)
    }
    if (next.adminToken !== undefined) {
      setAdminToken(next.adminToken)
    }
    // Re-read from storage instead of trusting the argument: `setTenantToken` trims and
    // may no-op on a storage failure, and the store must reflect what a request will
    // actually send.
    set({ tenantToken: getTenantToken(), adminToken: getAdminToken() })
  },

  clearCredentials: () => {
    clearStoredCredentials()
    set({ tenantToken: '', adminToken: '' })
  },

  openSettings: () => set({ settingsOpen: true }),
  closeSettings: () => set({ settingsOpen: false }),
}))
