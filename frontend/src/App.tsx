import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from '@/components/AppShell'
import { EventStreamPage } from '@/pages/EventStreamPage'
import { DeliveriesPage } from '@/pages/DeliveriesPage'
import { EndpointsPage } from '@/pages/EndpointsPage'
import { TenantsPage } from '@/pages/TenantsPage'
import { OverviewPage } from '@/pages/OverviewPage'

/**
 * Routes are flat and single-segment on purpose: the backend forwards `/ui/**` to
 * `index.html` only for paths without a dot, which is exactly `/ui/<one-segment>`.
 * A nested route (`/ui/tenants/1`) would 404 on refresh until that rule is widened —
 * the detail view is a drawer here, not a route, so nothing depends on it.
 */
export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/events" replace />} />
        <Route path="events" element={<EventStreamPage />} />
        <Route path="deliveries" element={<DeliveriesPage />} />
        <Route path="endpoints" element={<EndpointsPage />} />
        <Route path="tenants" element={<TenantsPage />} />
        <Route path="monitor" element={<OverviewPage />} />
        <Route path="*" element={<Navigate to="/events" replace />} />
      </Route>
    </Routes>
  )
}
