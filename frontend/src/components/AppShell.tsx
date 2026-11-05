import { useState } from 'react'
import { App, Badge, Button, Layout, Menu, Space, Tag, Tooltip } from 'antd'
import {
  ApiOutlined,
  DashboardOutlined,
  LinkOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SendOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { logout as endSession, useSession } from '@/api/session'
import { TokenDrawer } from './TokenDrawer'
import { useUiStore } from '@/stores/uiStore'

const { Header, Sider, Content } = Layout

const NAV_ITEMS = [
  { key: '/events', icon: <ThunderboltOutlined />, label: <Link to="/events">事件流</Link> },
  { key: '/deliveries', icon: <SendOutlined />, label: <Link to="/deliveries">投递</Link> },
  { key: '/endpoints', icon: <LinkOutlined />, label: <Link to="/endpoints">Endpoints</Link> },
  { key: '/tenants', icon: <TeamOutlined />, label: <Link to="/tenants">租户</Link> },
  { key: '/monitor', icon: <DashboardOutlined />, label: <Link to="/monitor">系统监控</Link> },
]

/**
 * The shell: navigation on the left, the operator identity and the tenant
 * credential entry point in the header.
 *
 * The identity comes from the server (`/api/auth/session`), never from local
 * storage. The role tag is an affordance, not the authorization: a viewer who
 * edits the DOM still gets a 403 from `/v1/admin/**`. The tenant API Key is a
 * separate trust domain and remains a drawer because it is a secret the operator
 * pastes, not a session.
 *
 * The sider folds below `lg` and can be toggled by hand, which is the whole responsive
 * story for an operator console — these tables are meant to be read on a laptop, and
 * pretending a four-column table works at 375px would be a worse experience than
 * horizontal scrolling inside the table (which AntD already does).
 */
export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [collapsed, setCollapsed] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const openSettings = useUiStore((state) => state.openSettings)
  const tenantToken = useUiStore((state) => state.tenantToken)

  const { data: session } = useSession()
  const isAdmin = session?.roles.includes('ADMIN') ?? false
  const missingTenantKey = tenantToken === ''

  const selectedKey =
    NAV_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? '/events'

  const onLogout = async () => {
    setLoggingOut(true)
    try {
      await endSession()
      queryClient.clear()
      navigate('/login', { replace: true })
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '退出失败')
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <Layout className="min-h-screen">
      <Sider
        collapsible
        collapsed={collapsed}
        trigger={null}
        breakpoint="lg"
        collapsedWidth={0}
        onBreakpoint={setCollapsed}
        width={208}
        theme="dark"
      >
        <div className="flex h-14 items-center gap-2 px-4 text-white">
          <ApiOutlined />
          {!collapsed && <span className="font-semibold tracking-wide">HookFlow</span>}
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[selectedKey]} items={NAV_ITEMS} />
      </Sider>

      <Layout>
        <Header className="flex items-center justify-between !px-4 shadow-sm">
          <Button
            type="text"
            aria-label={collapsed ? '展开导航' : '收起导航'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((value) => !value)}
          />

          <Space size="middle">
            <Space size={6} className="hidden sm:flex">
              <UserOutlined />
              <span className="text-sm">{session?.username ?? '—'}</span>
              <Tag color={isAdmin ? 'gold' : 'default'} className="!mr-0">
                {isAdmin ? '管理员' : '只读'}
              </Tag>
            </Space>

            <Tooltip title={missingTenantKey ? '尚未配置租户 API Key' : '配置租户 API Key'}>
              <Badge dot={missingTenantKey} offset={[-2, 2]}>
                <Button icon={<ApiOutlined />} onClick={openSettings}>
                  凭证
                </Button>
              </Badge>
            </Tooltip>

            <Tooltip title="退出登录">
              <Button
                aria-label="退出登录"
                icon={<LogoutOutlined />}
                loading={loggingOut}
                onClick={() => void onLogout()}
              />
            </Tooltip>
          </Space>
        </Header>

        <Content className="p-4 md:p-6">
          <Outlet />
        </Content>
      </Layout>

      <TokenDrawer />
    </Layout>
  )
}
