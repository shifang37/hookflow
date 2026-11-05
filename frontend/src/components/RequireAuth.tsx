import { Button, Result, Spin } from 'antd'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useSession } from '@/api/session'

/**
 * The client-side half of "the console requires login".
 *
 * The server already redirects `/ui/**` to `/ui/login`, so this exists for the
 * states the server cannot see: a session that expired while a tab was open, or a
 * 401 on the first admin poll. Rendering the shell while the answer is unknown
 * would flash navigation and fire a burst of failing requests, so the gate is a
 * spinner until the session probe resolves.
 */
export function RequireAuth() {
  const location = useLocation()
  const { data, isPending, isError, refetch } = useSession()

  if (isPending) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spin size="large" tip="正在确认登录状态…">
          <div className="h-24 w-48" />
        </Spin>
      </div>
    )
  }

  if (isError) {
    return (
      <Result
        status="warning"
        title="无法确认登录状态"
        subTitle="后端没有响应，或会话服务暂时不可用。"
        extra={
          <Button type="primary" onClick={() => void refetch()}>
            重试
          </Button>
        }
      />
    )
  }

  if (!data?.authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}
