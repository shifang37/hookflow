import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp } from 'antd'
import { sessionQueryKey, type Session } from '@/api/session'

const ADMIN: Session = { authenticated: true, username: 'admin', roles: ['ADMIN'] }

/**
 * The providers every page assumes exist: AntD's context (message/modal) and a
 * React Query client.
 *
 * A fresh `QueryClient` per render, with retries off: a retry would turn a deliberately
 * failing request into a three-attempt test that is slower and asserts nothing extra.
 *
 * The session is seeded rather than fetched. A page test is about the page, not about
 * the login round-trip — and an unseeded query would fire a real `/api/auth/session`
 * request from jsdom. Pass `{ session }` to test the viewer/read-only path.
 */
export function renderWithProviders(ui: ReactElement, options?: { session?: Session }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  queryClient.setQueryData(sessionQueryKey, options?.session ?? ADMIN)
  const result = render(
    <AntApp>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </AntApp>,
  )
  return { ...result, queryClient }
}

export { ADMIN as adminSession }
