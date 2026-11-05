import { useQuery } from '@tanstack/react-query'
import { http, toApiError } from './client'

/**
 * The operator identity, as the server sees it.
 *
 * This is a query rather than store state on purpose: the session lives in an
 * HttpOnly cookie that JavaScript cannot read, so the only trustworthy answer is
 * the one `/api/auth/session` returns. Caching it in React Query also gives the
 * app one place to invalidate after login, logout or a 401.
 */
export interface Session {
  authenticated: boolean
  username: string | null
  roles: string[]
}

export const ANONYMOUS: Session = { authenticated: false, username: null, roles: [] }

export const sessionQueryKey = ['session'] as const

export async function fetchSession(): Promise<Session> {
  try {
    const response = await http.get<Session>('/api/auth/session')
    return response.data
  } catch (error) {
    throw toApiError(error)
  }
}

/**
 * Form login, then a second session read.
 *
 * The first GET is what gives a fresh browser a CSRF cookie. The second one is
 * not redundant: Spring Security rotates the CSRF token on authentication, and the
 * next write (create tenant, update policy, logout) would otherwise carry the token
 * from before the login.
 */
export async function login(username: string, password: string): Promise<Session> {
  await fetchSession()
  const body = new URLSearchParams({ username, password }).toString()
  try {
    await http.post('/api/auth/login', body, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
  } catch (error) {
    throw toApiError(error)
  }
  return fetchSession()
}

export async function logout(): Promise<void> {
  try {
    await http.post('/api/auth/logout')
  } catch (error) {
    throw toApiError(error)
  }
}

export function useSession() {
  return useQuery({
    queryKey: sessionQueryKey,
    queryFn: fetchSession,
    // The server is the only authority; do not keep a stale identity around long
    // enough to show admin buttons after the session has ended.
    staleTime: 30_000,
    retry: false,
  })
}

/** Role checks are UI affordances; the server enforces the same matrix. */
export function useIsAdmin(): boolean {
  return useSession().data?.roles.includes('ADMIN') ?? false
}
