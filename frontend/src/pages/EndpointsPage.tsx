import { useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  POLL_INTERVAL_MS,
  useCreateEndpoint,
  useDeleteEndpoint,
  useEndpoints,
  useSetEndpointStatus,
  type EndpointQuery,
} from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import { WILDCARD_SUBSCRIPTION, type CreateEndpointRequest, type Endpoint, type EndpointCreated } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { SecretRevealModal } from '@/components/SecretRevealModal'
import { EndpointStatusTag, LiveBadge } from '@/components/StatusTag'

const PAGE_SIZE = 20

/**
 * Only catches obvious typos. The backend re-validates with a real URI parser, a DNS
 * lookup and an egress check (private ranges and metadata addresses are refused) —
 * none of which a browser can do. Duplicating the easy half here is enough to keep a
 * round trip out of the common mistake.
 */
const URL_PATTERN = /^https?:\/\/[^\s]+$/i

/** `*`, or a comma-separated list of event types; each must match the backend's charset. */
const SUBSCRIPTION_PATTERN = /^(\*|[A-Za-z0-9._-]+(\s*,\s*[A-Za-z0-9._-]+)*)$/

const DEFAULT_MAX_ATTEMPTS = 8

interface CreateFormValues {
  url: string
  subscribedTypes?: string
  maxAttempts: number
  maxConcurrency: number
}

/**
 * Endpoint management: the callback targets an event is fanned out to.
 *
 * This is the only page with a create form, and it is the only place in the console
 * where a secret is handed to the user, so the flow is deliberately two dialogs: the
 * form, then the secret — the second one is not dismissible by clicking outside.
 */
export function EndpointsPage() {
  const { message } = App.useApp()
  const [page, setPage] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [created, setCreated] = useState<EndpointCreated | undefined>(undefined)

  const query = useMemo<EndpointQuery>(() => ({ page, size: PAGE_SIZE }), [page])
  const { data, error, isFetching, refetch } = useEndpoints(query)
  const statusMutation = useSetEndpointStatus()
  const deleteMutation = useDeleteEndpoint()

  const toggleStatus = (endpoint: Endpoint, action: 'pause' | 'resume') => {
    statusMutation.mutate(
      { endpointId: endpoint.id, action },
      {
        onSuccess: () =>
          message.success(action === 'pause' ? `已暂停 #${endpoint.id}` : `已恢复 #${endpoint.id}`),
        onError: (cause) => message.error(cause.message),
      },
    )
  }

  const deleteEndpoint = (endpoint: Endpoint) => {
    deleteMutation.mutate(endpoint.id, {
      onSuccess: () => message.success(`已删除 #${endpoint.id}`),
      onError: (cause) => message.error(cause.message),
    })
  }

  const columns: TableProps<Endpoint>['columns'] = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    {
      title: '回调地址',
      dataIndex: 'url',
      key: 'url',
      render: (value: string) => (
        <Tooltip title={value}>
          <Typography.Text className="!max-w-[320px]" ellipsis>
            {value}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '订阅类型',
      dataIndex: 'subscribedTypes',
      key: 'subscribedTypes',
      width: 240,
      render: (value: string) => <SubscriptionTags value={value} />,
    },
    {
      title: '尝试上限',
      dataIndex: 'maxAttempts',
      key: 'maxAttempts',
      width: 96,
      render: (value: number) => <span className="tabular-nums">{value}</span>,
    },
    {
      title: '并发上限',
      dataIndex: 'maxConcurrency',
      key: 'maxConcurrency',
      width: 96,
      render: (value: number) => (
        <span className="tabular-nums">{value === 0 ? '不限' : value}</span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: Endpoint['status']) => <EndpointStatusTag status={value} />,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (value: string) => <span className="tabular-nums">{formatTimestamp(value)}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 188,
      render: (_, endpoint) => {
        const busy =
          statusMutation.isPending && statusMutation.variables?.endpointId === endpoint.id
        const deleting = deleteMutation.isPending && deleteMutation.variables === endpoint.id
        return (
          <Space size={4}>
            {endpoint.status === 'ACTIVE' ? (
              <Popconfirm
                title="暂停这个 Endpoint？"
                description="会立刻取消它下面所有 PENDING / RETRY_WAIT 的投递；已经在投递中的不受影响。"
                okText="暂停"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => toggleStatus(endpoint, 'pause')}
              >
                <Button size="small" danger loading={busy}>
                  暂停
                </Button>
              </Popconfirm>
            ) : (
              <Popconfirm
                title="恢复这个 Endpoint？"
                description="恢复后只接收新事件；已取消的投递不会复活，需要按条重放。"
                okText="恢复"
                cancelText="取消"
                onConfirm={() => toggleStatus(endpoint, 'resume')}
              >
                <Button size="small" type="primary" loading={busy}>
                  恢复
                </Button>
              </Popconfirm>
            )}
            <Popconfirm
              title="删除这个 Endpoint？"
              description="会取消它下面所有 PENDING / RETRY_WAIT 的投递，且无法恢复。已经在投递中的不受影响；之后可以用同一个地址重新创建。"
              okText="确认删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => deleteEndpoint(endpoint)}
            >
              <Button size="small" danger type="text" loading={deleting}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        )
      },
    },
  ]

  return (
    <div>
      <PageTitle
        title="Endpoints"
        subtitle="客户的回调目标 · 需要租户 API Key（创建/暂停/删除还需要 endpoint:write 权限）"
        actions={
          <Space>
            <LiveBadge intervalMs={POLL_INTERVAL_MS.endpoints} fetching={isFetching} />
            <Button icon={<ReloadOutlined />} size="small" onClick={() => void refetch()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} size="small" onClick={() => setCreateOpen(true)}>
              新增 Endpoint
            </Button>
          </Space>
        }
      />

      <CredentialAlert scope="tenant" />

      {error !== null && !(error instanceof ApiError && error.status === 401) && (
        <Alert
          className="mb-4"
          type="error"
          showIcon
          message="读取 Endpoint 失败"
          description={error.message}
          action={
            <Button size="small" onClick={() => void refetch()}>
              重试
            </Button>
          }
        />
      )}

      <Card styles={{ body: { padding: 0 } }}>
        <Table<Endpoint>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={isFetching && data === undefined}
          scroll={{ x: 1080 }}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total: data?.total ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setPage(nextPage - 1),
          }}
          locale={{ emptyText: '还没有 Endpoint。点右上角「新增 Endpoint」建一个。' }}
          expandable={{
            // The secret is never readable after creation, so the row cannot show it.
            // What it *can* show is what the receiver must do to verify a signature.
            expandedRowRender: (endpoint) => <EndpointHints endpoint={endpoint} />,
            rowExpandable: () => true,
          }}
        />
      </Card>

      <CreateEndpointModal
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onCreated={(endpoint) => {
          setCreateOpen(false)
          setCreated(endpoint)
          setPage(0)
        }}
      />

      <SecretRevealModal
        open={created !== undefined}
        title="Endpoint 已创建"
        alertMessage="签名密钥只显示这一次"
        description="服务端只存加密后的密文，之后任何接口都不会再返回明文。现在不保存，就只能重建一个 Endpoint。"
        value={created?.signingSecret ?? ''}
        valueLabel="signing secret"
        subject={created === undefined ? undefined : `#${created.id} · ${created.url}`}
        onClose={() => setCreated(undefined)}
      />
    </div>
  )
}

function SubscriptionTags({ value }: { value: string }) {
  const types = value
    .split(',')
    .map((type) => type.trim())
    .filter((type) => type !== '')

  if (types.length === 1 && types[0] === WILDCARD_SUBSCRIPTION) {
    return <Tag color="blue">全部事件</Tag>
  }

  const visible = types.slice(0, 3)
  const hidden = types.length - visible.length
  return (
    <Space size={4} wrap>
      {visible.map((type) => (
        <Tag key={type} className="!mr-0">
          {type}
        </Tag>
      ))}
      {hidden > 0 && (
        <Tooltip title={types.slice(3).join(', ')}>
          <Tag className="!mr-0">+{hidden}</Tag>
        </Tooltip>
      )}
    </Space>
  )
}

function EndpointHints({ endpoint }: { endpoint: Endpoint }) {
  return (
    <div className="text-xs text-gray-600">
      <div>
        接收方用建 Endpoint 时拿到的那串 secret 验证签名：
        <Typography.Text code className="!text-xs">
          HMAC-SHA256(secret, "v1:" + eventId + ":" + deliveryId + ":" + timestamp + ":" + rawBody)
        </Typography.Text>
        ，结果写成 <Typography.Text code className="!text-xs">v1=&lt;hex&gt;</Typography.Text> 与{' '}
        <Typography.Text code className="!text-xs">X-HookFlow-Signature</Typography.Text> 比对，
        并校验 <Typography.Text code className="!text-xs">X-HookFlow-Timestamp</Typography.Text>
        （epoch 秒）在 ±300s 内。
      </div>
      <div className="mt-1">
        {endpoint.status === 'PAUSED'
          ? '当前已暂停：新事件不会投给这个地址，暂停时被取消的投递不会自动恢复。'
          : '正在接收新事件；同一个事件只会投给这个地址一次（按 event_id 去重即可）。'}
      </div>
    </div>
  )
}

function CreateEndpointModal({
  open,
  onCancel,
  onCreated,
}: {
  open: boolean
  onCancel: () => void
  onCreated: (endpoint: EndpointCreated) => void
}) {
  const [form] = Form.useForm<CreateFormValues>()
  const create = useCreateEndpoint()
  const { message } = App.useApp()

  const submit = (values: CreateFormValues) => {
    const body: CreateEndpointRequest = {
      url: values.url.trim(),
      maxAttempts: values.maxAttempts,
      maxConcurrency: values.maxConcurrency,
    }
    if (values.subscribedTypes !== undefined && values.subscribedTypes.trim() !== '') {
      body.subscribedTypes = values.subscribedTypes.trim()
    }
    create.mutate(body, {
      onSuccess: (endpoint) => {
        form.resetFields()
        onCreated(endpoint)
      },
      onError: (cause) => {
        message.error(cause.message)
      },
    })
  }

  return (
    <Modal
      title="新增 Endpoint"
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
        onFinish={submit}
        initialValues={{
          subscribedTypes: WILDCARD_SUBSCRIPTION,
          maxAttempts: DEFAULT_MAX_ATTEMPTS,
          maxConcurrency: 0,
        }}
      >
        <Form.Item
          label="回调地址"
          name="url"
          rules={[
            { required: true, message: '请输入回调地址' },
            { pattern: URL_PATTERN, message: '必须是 http:// 或 https:// 开头的完整地址' },
          ]}
          extra="后端会再做一次真实校验：域名能解析、且不是内网或云元数据地址。"
        >
          <Input placeholder="https://customer.example/hooks/hookflow" />
        </Form.Item>

        <Form.Item
          label="订阅类型"
          name="subscribedTypes"
          rules={[
            {
              validator: (_, value: string | undefined) => {
                if (value === undefined || value.trim() === '') {
                  return Promise.resolve()
                }
                return SUBSCRIPTION_PATTERN.test(value.trim())
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error('用 * 表示全部，或用逗号分隔的事件类型（只允许字母数字和 ._-）'),
                    )
              },
            },
          ]}
          extra="精确匹配，不是前缀匹配：order.paid 不会匹配到 order.paid.v2。"
        >
          <Input placeholder="* 或 order.paid,order.refunded" />
        </Form.Item>

        <div className="grid grid-cols-2 gap-3">
          <Form.Item
            label="尝试上限"
            name="maxAttempts"
            rules={[{ required: true, message: '必填' }]}
            extra="1–50。用完后投递进入死信，可手动重放。"
          >
            <InputNumber min={1} max={50} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="并发上限"
            name="maxConcurrency"
            rules={[{ required: true, message: '必填' }]}
            extra="0 = 不限。接收方扛不住并发时填它。"
          >
            <InputNumber min={0} max={1000} style={{ width: '100%' }} />
          </Form.Item>
        </div>
      </Form>
    </Modal>
  )
}

