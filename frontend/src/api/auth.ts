/**
 * Tenant credential storage.
 *
 * The console login is a server-side session (HttpOnly cookie), so the operator
 * credential is no longer readable — or writable — from JavaScript. What remains
 * here is the *other* trust domain: a tenant API key that authorizes the
 * event/delivery/endpoint pages. It is kept in localStorage because the same demo
 * tenant is usually reused across reloads, and it is never echoed back into the DOM.
 */

const TENANT_STORAGE_KEY = 'hookflow.tenant-token'

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

export function clearCredentials(): void {
  writeToken(TENANT_STORAGE_KEY, '')
}
