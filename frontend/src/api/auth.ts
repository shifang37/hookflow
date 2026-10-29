/**
 * Two credentials, two trust domains — the same split the backend enforces.
 *
 *   tenant token   `Authorization: Bearer hk_...`  → `/v1/events`, `/v1/deliveries`
 *   admin token    `X-Bootstrap-Token: ...`        → `/v1/admin/**`
 *
 * Precedence is localStorage over the build-time default, so a demo can ship with
 * `.env.local` values and a reviewer can still paste their own without rebuilding.
 * Values are never logged and never rendered back into the DOM.
 */

const TENANT_STORAGE_KEY = 'hookflow.tenant-token'
const ADMIN_STORAGE_KEY = 'hookflow.admin-token'

function readToken(storageKey: string, fallback: string | undefined): string {
  try {
    const stored = window.localStorage.getItem(storageKey)
    if (stored && stored.trim() !== '') {
      return stored.trim()
    }
  } catch {
    // Private-mode browsers throw on localStorage access. A missing credential is a
    // recoverable state (the UI asks for one); an exception here would blank the app.
  }
  return (fallback ?? '').trim()
}

function writeToken(storageKey: string, token: string): void {
  try {
    if (token.trim() === '') {
      window.localStorage.removeItem(storageKey)
    } else {
      window.localStorage.setItem(storageKey, token.trim())
    }
  } catch {
    // Same as above: the in-memory value still applies for this session.
  }
}

export function getTenantToken(): string {
  return readToken(TENANT_STORAGE_KEY, import.meta.env.VITE_HOOKFLOW_TOKEN)
}

export function setTenantToken(token: string): void {
  writeToken(TENANT_STORAGE_KEY, token)
}

export function getAdminToken(): string {
  return readToken(ADMIN_STORAGE_KEY, import.meta.env.VITE_HOOKFLOW_BOOTSTRAP_TOKEN)
}

export function setAdminToken(token: string): void {
  writeToken(ADMIN_STORAGE_KEY, token)
}

export function clearCredentials(): void {
  writeToken(TENANT_STORAGE_KEY, '')
  writeToken(ADMIN_STORAGE_KEY, '')
}
