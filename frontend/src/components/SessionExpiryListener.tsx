import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { SESSION_EXPIRED_EVENT } from '@/api/client'
import { ANONYMOUS, sessionQueryKey } from '@/api/session'

/**
 * Turns an admin call's 401 into a client-side logout.
 *
 * The event is dispatched by `client.ts`; this component does not navigate. It
 * writes the anonymous identity into the session cache and lets `RequireAuth`
 * route to `/login`, so there is exactly one redirect rule instead of two that can
 * disagree during a render.
 */
export function SessionExpiryListener() {
  const queryClient = useQueryClient()

  useEffect(() => {
    const expire = () => queryClient.setQueryData(sessionQueryKey, ANONYMOUS)
    window.addEventListener(SESSION_EXPIRED_EVENT, expire)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, expire)
  }, [queryClient])

  return null
}
