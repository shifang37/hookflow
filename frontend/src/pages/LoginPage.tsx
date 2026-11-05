import { useState } from 'react'
import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd'
import { ApiOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { useQueryClient } from '@tanstack/react-query'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { login, sessionQueryKey, useSession } from '@/api/session'

interface FormValues {
  username: string
  password: string
}

/**
 * The only screen outside the authenticated shell.
 *
 * It is a client route (`/ui/login` in the built app), but the POST goes to the
 * server: `/api/auth/login` is Spring Security's form-login URL, and the response
 * sets the HttpOnly session cookie. The page never stores a token, which is the
 * point — a password in localStorage is a password one XSS away from exfiltration.
 */
export function LoginPage() {
  const session = useSession()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  const from = (location.state as { from?: string } | null)?.from ?? '/events'

  if (session.data?.authenticated === true) {
    return <Navigate to={from} replace />
  }

  const onFinish = async (values: FormValues) => {
    setSubmitting(true)
    setError(undefined)
    try {
      const next = await login(values.username, values.password)
      queryClient.setQueryData(sessionQueryKey, next)
      navigate(from, { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '登录失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 p-4">
      <Card className="w-full max-w-sm shadow-sm">
        <Space direction="vertical" size="large" className="w-full">
          <div className="text-center">
            <div className="mb-2 text-3xl text-blue-600">
              <ApiOutlined />
            </div>
            <Typography.Title level={3} className="!mb-1">
              HookFlow 控制台
            </Typography.Title>
            <Typography.Text type="secondary">使用管理员分配的账号登录</Typography.Text>
          </div>

          {error !== undefined && (
            <Alert type="error" showIcon message="登录失败" description={error} />
          )}

          <Form<FormValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
            <Form.Item
              label="用户名"
              name="username"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="admin"
                autoComplete="username"
                autoFocus
              />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="••••••••"
                autoComplete="current-password"
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form>

          <Typography.Text type="secondary" className="!text-xs">
            会话保存在 HttpOnly Cookie 中；所有修改操作会校验角色和 CSRF 令牌。
          </Typography.Text>
        </Space>
      </Card>
    </div>
  )
}
