import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import './index.css'
import { App } from './App'
import { ApiError } from './api/client'
import { antdTheme } from './theme'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // A 4xx is a mistake in the request — a missing credential, a bad filter, a row
      // that is gone. Retrying it twice triples the time before the user sees the
      // message, and the second and third attempts fail identically.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.isClientError) && failureCount < 2,
      // Operator consoles are usually left open on a second monitor; refetching on every
      // focus would fire whatever polls were paused and is not what "live" means here.
      refetchOnWindowFocus: false,
    },
  },
})

// `/ui/` in the production build, `/` in dev — the same value Vite uses for assets, so
// the router and the bundle can never disagree about where the app lives.
const basename = import.meta.env.BASE_URL.replace(/\/$/, '') || '/'

const container = document.getElementById('root')
if (container === null) {
  throw new Error('#root is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <ConfigProvider locale={zhCN} theme={antdTheme}>
      {/* AntApp provides message/notification/modal contexts that survive re-renders. */}
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter basename={basename}>
            <App />
          </BrowserRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
)
