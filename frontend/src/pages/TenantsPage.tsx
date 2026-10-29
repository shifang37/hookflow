import { useMemo, useState } from 'react'
import { Alert, Button, Card, Input, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  blankToUndefined,
  POLL_INTERVAL_MS,
  useTenants,
  type TenantQuery,
} from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import type { Tenant, TenantProvisioned } from '@/api/types'
import { CreateTenantModal } from '@/components/CreateTenantModal'
import { CredentialAlert } from '@/components/CredentialAlert'
import { PageTitle } from '@/components/PageTitle'
import { SecretRevealModal } from '@/components/SecretRevealModal'
import { LiveBadge, TenantStatusTag } from '@/components/StatusTag'
import { TenantDetailDrawer } from '@/components/TenantDetailDrawer'

const PAGE_SIZE = 20

/**
 * Tenant management: the operator domain, so this page uses the bootstrap token.
 *
 * The table is the whole page: search, the list, and the three actions a row can start
 * (open the drawer, create a tenant, reveal a freshly provisioned key). The drawer and
 * the modal live in `components/` because each is a screen of its own — the drawer
 * carries the statistics, the policy form and key rotation, and the modal carries the
 * provisioning form.
 */
export function TenantsPage() {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Tenant | undefined>(undefined)
  const [createOpen, setCreateOpen] = useState(false)
  const [provisioned, setProvisioned] = useState<TenantProvisioned | undefined>(undefined)

  const query = useMemo<TenantQuery>(
    () => ({ search: blankToUndefined(search), page, size: PAGE_SIZE }),
    [search, page],
  )
  const { data, error, isFetching, refetch } = useTenants(query)

  const columns: TableProps<Tenant>['columns'] = [
    { title: 'ID', dataIndex: 'tenantId', key: 'tenantId', width: 80 },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (value: string) => <Typography.Text strong>{value}</Typography.Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 96,
      render: (value: Tenant['status']) => <TenantStatusTag status={value} />,
    },
    {
      title: '限流（事件/秒）',
      dataIndex: 'rateLimit',
      key: 'rateLimit',
      width: 140,
      render: (value: number) => <span className="tabular-nums">{value === 0 ? '不限' : value}</span>,
    },
    {
      title: '并发预算',
      dataIndex: 'maxInFlight',
      key: 'maxInFlight',
      width: 112,
      render: (value: number) => <span className="tabular-nums">{value === 0 ? '不限' : value}</span>,
    },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 80 },
    { title: 'Endpoints', dataIndex: 'endpointCount', key: 'endpointCount', width: 104 },
    { title: '有效 Key', dataIndex: 'activeKeyCount', key: 'activeKeyCount', width: 96 },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (value: string) => <span className="tabular-nums">{formatTimestamp(value)}</span>,
    },
  ]

  return (
    <div>
      <PageTitle
        title="租户"
        subtitle="运营域 · 需要 X-Bootstrap-Token · 点一行查看统计并调整策略"
        actions={
          <span className="flex items-center gap-2">
            <LiveBadge intervalMs={POLL_INTERVAL_MS.tenants} fetching={isFetching} />
            <Button icon={<ReloadOutlined />} size="small" onClick={() => void refetch()}>
              刷新
            </Button>
            <Button
              type="primary"
              size="small"
              icon={<PlusOutlined />}
              onClick={() => setCreateOpen(true)}
            >
              创建租户
            </Button>
          </span>
        }
      />

      <CredentialAlert scope="admin" />

      {error !== null && !(error instanceof ApiError && error.status === 401) && (
        <Alert
          className="mb-4"
          type="error"
          showIcon
          message="读取租户失败"
          description={error.message}
          action={
            <Button size="small" onClick={() => void refetch()}>
              重试
            </Button>
          }
        />
      )}

      <Card styles={{ body: { padding: 0 } }}>
        <div className="border-b border-gray-100 p-3">
          <Input.Search
            allowClear
            placeholder="按名称搜索"
            style={{ maxWidth: 320 }}
            onSearch={(value) => {
              setSearch(value)
              setPage(0)
            }}
          />
        </div>

        <Table<Tenant>
          rowKey="tenantId"
          size="middle"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={isFetching && data === undefined}
          onRow={(tenant) => ({
            onClick: () => setSelected(tenant),
            style: { cursor: 'pointer' },
          })}
          scroll={{ x: 1100 }}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total: data?.total ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setPage(nextPage - 1),
          }}
          locale={{ emptyText: '还没有租户。用 POST /v1/admin/tenants 建一个。' }}
        />
      </Card>

      <TenantDetailDrawer
        tenant={selected}
        onClose={() => setSelected(undefined)}
        onUpdated={setSelected}
      />

      <CreateTenantModal
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onCreated={(created) => {
          setCreateOpen(false)
          setProvisioned(created)
          // Newest-first list: the new tenant is on page 0, not wherever the operator was.
          setPage(0)
        }}
      />

      <SecretRevealModal
        open={provisioned !== undefined}
        title="租户已创建"
        alertMessage="API Key 只显示这一次"
        description="服务端只存 SHA-256 哈希，之后任何接口都不会再返回明文。现在不保存，就只能进这个租户的抽屉再生成一把。"
        value={provisioned?.apiKey ?? ''}
        valueLabel="provisioned api key"
        subject={
          provisioned === undefined
            ? undefined
            : `#${provisioned.tenantId} · ${provisioned.name} · 前缀 ${provisioned.keyPrefix}`
        }
        onClose={() => setProvisioned(undefined)}
      />
    </div>
  )
}
