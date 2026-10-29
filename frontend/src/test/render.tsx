import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp } from 'antd'

/**
 * The providers every page assumes exist: AntD's context (message/modal) and a
 * React Query client.
 *
 * A fresh `QueryClient` per render, with retries off: a retry would turn a deliberately
 * failing request into a three-attempt test that is slower and asserts nothing extra.
 */
export function renderWithProviders(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <AntApp>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </AntApp>,
  )
}
