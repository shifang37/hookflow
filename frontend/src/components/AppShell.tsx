import { useState } from 'react'
import { Badge, Button, Layout, Menu, Tooltip } from 'antd'
import {
  ApiOutlined,
  DashboardOutlined,
  LinkOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SendOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { Link, Outlet, useLocation } from 'react-router-dom'
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
 * The shell: navigation on the left, one credential entry point in the header.
 *
 * The sider folds below `lg` and can be toggled by hand, which is the whole responsive
 * story for an operator console — these tables are meant to be read on a laptop, and
 * pretending a four-column table works at 375px would be a worse experience than
 * horizontal scrolling inside the table (which AntD already does).
 */
export function AppShell() {
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const openSettings = useUiStore((state) => state.openSettings)
  const tenantToken = useUiStore((state) => state.tenantToken)
  const adminToken = useUiStore((state) => state.adminToken)

  const missingCredentials = tenantToken === '' || adminToken === ''

  const selectedKey =
    NAV_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? '/events'

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
          <Tooltip title={missingCredentials ? '租户 Key 或运营令牌尚未配置' : '两个凭证都已配置'}>
            <Badge dot={missingCredentials} offset={[-2, 2]}>
              <Button icon={<ApiOutlined />} onClick={openSettings}>
                凭证
              </Button>
            </Badge>
          </Tooltip>
        </Header>

        <Content className="p-4 md:p-6">
          <Outlet />
        </Content>
      </Layout>

      <TokenDrawer />
    </Layout>
  )
}
