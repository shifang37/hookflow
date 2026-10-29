import { useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Popconfirm,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  blankToUndefined,
  POLL_INTERVAL_MS,
  useCreateTenant,
  useIssueTenantKey,
  useRevokeApiKey,
  useTenants,
  useTenantKeys,
  useTenantStats,
  useUpdateTenant,
  type TenantQuery,
} from '@/api/queries'
import { formatCount, formatMillis, formatPercent, formatTimestamp } from '@/api/time'
import type { IssuedKey, Tenant, TenantProvisioned, TenantStatus } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { SecretRevealModal } from '@/components/SecretRevealModal'
import { LiveBadge } from '@/components/StatusTag'
import { useUiStore } from '@/stores/uiStore'

const PAGE_SIZE = 20

function TenantStatusTag({ status }: { status: TenantStatus }) {
  return status === 'ACTIVE' ? <Tag color="success">启用</Tag> : <Tag color="error">停用</Tag>
}

/**
 * Tenant management: the operator domain, so this page uses the bootstrap token.
 *
 * The drawer is where a tenant's numbers and its policy meet — the four statistics are
 * what an operator looks at, the three inputs are what they change in response. Keeping
 * them on one screen is the difference between "the tenant is at 60% failure" and
 * "…and here is the knob".
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
      render: (value: TenantStatus) => <TenantStatusTag status={value} />,
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

interface CreateTenantValues {
  name: string
  rateLimit: number
  maxInFlight: number
  weight: number
}

/** Mirrors the backend defaults in {@code AdminController.provisionTenant}. */
const DEFAULT_RATE_LIMIT = 100
const DEFAULT_MAX_IN_FLIGHT = 32

/**
 * Provisioning a tenant: the last operation that used to require curl.
 *
 * <p>The form mirrors the server's constraints (name ≤ 64 and unique, rate limit and
 * budget ≥ 0, weight 1–100) but does not try to be authoritative — a duplicate name is
 * still caught by {@code uk_tenant_name} and comes back as a 409 whose message is shown
 * verbatim. Client-side rules only exist to keep that round trip out of obvious mistakes.
 */
function CreateTenantModal({
  open,
  onCancel,
  onCreated,
}: {
  open: boolean
  onCancel: () => void
  onCreated: (created: TenantProvisioned) => void
}) {
  const [form] = Form.useForm<CreateTenantValues>()
  const create = useCreateTenant()
  const { message } = App.useApp()

  return (
    <Modal
      title="创建租户"
      open={open}
      onCancel={() => {
        form.resetFields()
        onCancel()
      }}
      onOk={() => form.submit()}
      okText="创建"
      cancelText="取消"
      confirmLoading={create.isPending}
      destroyOnHidden
      width={560}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          rateLimit: DEFAULT_RATE_LIMIT,
          maxInFlight: DEFAULT_MAX_IN_FLIGHT,
          weight: 1,
        }}
        onFinish={(values) =>
          create.mutate(
            {
              name: values.name.trim(),
              rateLimit: values.rateLimit,
              maxInFlight: values.maxInFlight,
              weight: values.weight,
            },
            {
              onSuccess: (created) => {
                form.resetFields()
                onCreated(created)
              },
              onError: (cause) => message.error(cause.message),
            },
          )
        }
      >
        <Form.Item
          label="租户名称"
          name="name"
          rules={[
            { required: true, message: '请输入租户名称' },
            { max: 64, message: '最多 64 个字符' },
          ]}
          extra="唯一。重名会返回 409，不会建出第二个租户。"
        >
          <Input placeholder="acme" />
        </Form.Item>

        <div className="grid grid-cols-3 gap-3">
          <Form.Item
            label="限流（事件/秒）"
            name="rateLimit"
            rules={[{ required: true, message: '必填' }]}
            extra="0 = 不限速"
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="并发预算"
            name="maxInFlight"
            tooltip="该租户同时在投递中的条数上限，决定它能排在别人前面多少条"
            rules={[{ required: true, message: '必填' }]}
            extra="0 = 不限"
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="公平权重"
            name="weight"
            tooltip="竞争时的加权轮询份额，不放大吞吐"
            rules={[{ required: true, message: '必填' }]}
            extra="1–100"
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </div>
      </Form>
    </Modal>
  )
}

function TenantDetailDrawer({
  tenant,
  onClose,
  onUpdated,
}: {
  tenant: Tenant | undefined
  onClose: () => void
  onUpdated: (tenant: Tenant) => void
}) {
  const { message } = App.useApp()
  const range = useUiStore((state) => state.range)
  const { data: stats, error } = useTenantStats(tenant?.tenantId, range)
  const update = useUpdateTenant()

  return (
    <Drawer
      title={tenant === undefined ? '租户' : `${tenant.name}`}
      open={tenant !== undefined}
      onClose={onClose}
      width={520}
      destroyOnHidden
    >
      {tenant === undefined ? null : (
        <div className="space-y-4">
          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label="ID">{tenant.tenantId}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <TenantStatusTag status={tenant.status} />
            </Descriptions.Item>
            <Descriptions.Item label="限流">{tenant.rateLimit} /秒</Descriptions.Item>
            <Descriptions.Item label="并发预算">{tenant.maxInFlight}</Descriptions.Item>
            <Descriptions.Item label="权重">{tenant.weight}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{formatTimestamp(tenant.createdAt)}</Descriptions.Item>
          </Descriptions>

          {error !== null && (
            <Alert type="error" showIcon message="读取统计失败" description={error.message} />
          )}

          <Card size="small" title={`近 ${range} 统计`}>
            <div className="grid grid-cols-2 gap-4">
              <Statistic title="事件数" value={formatCount(stats?.totalEvents)} />
              <Statistic
                title="成功率（已结算）"
                value={formatPercent(stats?.successRate)}
                valueStyle={{
                  color:
                    stats === undefined
                      ? undefined
                      : stats.successRate >= 95
                        ? '#3f8600'
                        : '#cf1322',
                }}
              />
              <Statistic title="平均端到端延迟" value={formatMillis(stats?.avgLatencyMs)} />
              <Statistic title="当前积压" value={formatCount(stats?.backlog)} />
            </div>
          </Card>

          <Card size="small" title="Top 失败原因">
            {stats !== undefined && stats.topFailureReasons.length > 0 ? (
              <List
                size="small"
                dataSource={stats.topFailureReasons}
                renderItem={(reason) => (
                  <List.Item>
                    <List.Item.Meta
                      title={
                        <Typography.Text code>
                          {reason.errorClass ?? 'UNCLASSIFIED'}
                          {reason.statusCode !== null && ` · ${reason.statusCode}`}
                        </Typography.Text>
                      }
                      description={`${reason.count} 次（${formatPercent(reason.percentage)}）`}
                    />
                  </List.Item>
                )}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={stats === undefined ? '加载中…' : '窗口内没有死信'}
              />
            )}
          </Card>

          <TenantKeysCard tenant={tenant} />

          <Card size="small" title="调整策略">
            <Form
              layout="vertical"
              key={tenant.tenantId}
              initialValues={{
                rateLimit: tenant.rateLimit,
                maxInFlight: tenant.maxInFlight,
                weight: tenant.weight,
              }}
              onFinish={(values: { rateLimit: number; maxInFlight: number; weight: number }) => {
                update.mutate(
                  { tenantId: tenant.tenantId, body: values },
                  {
                    onSuccess: (updated) => {
                      message.success('策略已更新，调度快照已刷新')
                      onUpdated(updated)
                    },
                    onError: (cause) => message.error(cause.message),
                  },
                )
              }}
            >
              <Form.Item
                label="限流（事件/秒，0 = 不限）"
                name="rateLimit"
                rules={[{ required: true }]}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                label="并发预算（0 = 不限）"
                name="maxInFlight"
                tooltip="该租户同时在投递中的条数上限，决定它能排在别人前面多少条"
                rules={[{ required: true }]}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                label="公平调度权重（1-100）"
                name="weight"
                tooltip="只影响竞争时的发布顺序份额，不放大吞吐"
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={100} style={{ width: '100%' }} />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={update.isPending}>
                保存
              </Button>
            </Form>
          </Card>
        </div>
      )}
    </Drawer>
  )
}

/**
 * Key rotation, inside the tenant drawer.
 *
 * Three states are deliberately visible at once: the live key (prefix + scopes), the
 * revoked ones (so "I rotated it" is provable after the fact), and the exact time each
 * happened. The plaintext never appears here — issuing it opens a separate dialog that
 * shows it once, because the server keeps only a hash and this list could not show it
 * even if the UI wanted to.
 */
function TenantKeysCard({ tenant }: { tenant: Tenant }) {
  const { message } = App.useApp()
  const { data: keys, error } = useTenantKeys(tenant.tenantId)
  const issue = useIssueTenantKey(tenant.tenantId)
  const revoke = useRevokeApiKey()
  const [issued, setIssued] = useState<IssuedKey | undefined>(undefined)

  return (
    <Card
      size="small"
      title="API Keys"
      extra={
        <Button
          size="small"
          type="primary"
          loading={issue.isPending}
          onClick={() =>
            issue.mutate(undefined, {
              onSuccess: (key) => setIssued(key),
              onError: (cause) => message.error(cause.message),
            })
          }
        >
          生成新 Key
        </Button>
      }
    >
      {error !== null && <Alert type="error" showIcon message="读取 API Key 失败" description={error.message} />}

      {keys !== undefined && keys.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有 API Key" />
      ) : (
        <List
          size="small"
          dataSource={keys ?? []}
          renderItem={(key) => (
            <List.Item
              actions={[
                <Popconfirm
                  key="revoke"
                  title="撤销这把 Key？"
                  description="撤销后立刻失效：用它发出的请求会开始返回 401，且不可恢复。"
                  okText="撤销"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  disabled={key.status === 'REVOKED'}
                  onConfirm={() =>
                    revoke.mutate(key.id, {
                      onSuccess: () => message.success('已撤销'),
                      onError: (cause) => message.error(cause.message),
                    })
                  }
                >
                  <Button size="small" danger disabled={key.status === 'REVOKED'}>
                    撤销
                  </Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                title={
                  <Space size={6}>
                    <Typography.Text code className="!text-xs">
                      hk_{key.keyPrefix}_…
                    </Typography.Text>
                    {key.status === 'ACTIVE' ? (
                      <Tag color="success">有效</Tag>
                    ) : (
                      <Tag>已撤销</Tag>
                    )}
                  </Space>
                }
                description={
                  <span className="text-xs text-gray-500">
                    {key.scopes} · 创建 {formatTimestamp(key.createdAt)}
                    {key.revokedAt !== null && ` · 撤销 ${formatTimestamp(key.revokedAt)}`}
                  </span>
                }
              />
            </List.Item>
          )}
        />
      )}

      <SecretRevealModal
        open={issued !== undefined}
        title="API Key 已生成"
        alertMessage="API Key 只显示这一次"
        description="服务端只存 SHA-256 哈希，之后任何接口都不会再返回明文。现在不保存，就只能撤销后重建一把。"
        value={issued?.apiKey ?? ''}
        valueLabel="api key"
        subject={issued === undefined ? undefined : `${tenant.name} · 前缀 ${issued.keyPrefix}`}
        onClose={() => setIssued(undefined)}
      />
    </Card>
  )
}
