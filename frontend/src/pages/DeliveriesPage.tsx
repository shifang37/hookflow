import { useMemo, useState } from 'react'
import { Alert, App, Button, Card, InputNumber, Popconfirm, Select, Space, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  POLL_INTERVAL_MS,
  useDeliveries,
  useReplayDelivery,
  type DeliveryQuery,
} from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import { DELIVERY_STATUSES, type Delivery, type DeliveryStatus } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { DeliveryStatusTag, LiveBadge, STATUS_META } from '@/components/StatusTag'

const PAGE_SIZE = 20

/**
 * Delivery inspection, and the console's only write: replaying a dead letter.
 *
 * Replay is offered only on `DEAD` rows because that is the state machine's rule
 * (ILLEGAL_TRANSITION otherwise) — the UI mirrors the backend's constraint instead of
 * letting the user discover it as a 409.
 */
export function DeliveriesPage() {
  const { message } = App.useApp()
  const [status, setStatus] = useState<DeliveryStatus | undefined>(undefined)
  const [endpointId, setEndpointId] = useState<number | null>(null)
  const [page, setPage] = useState(0)

  const query = useMemo<DeliveryQuery>(
    () => ({
      status,
      endpointId: endpointId ?? undefined,
      page,
      size: PAGE_SIZE,
    }),
    [status, endpointId, page],
  )

  const { data, error, isFetching, refetch } = useDeliveries(query)
  const replay = useReplayDelivery()

  const handleReplay = (deliveryId: number) => {
    replay.mutate(deliveryId, {
      onSuccess: () => message.success(`投递 #${deliveryId} 已重新入队`),
      onError: (cause) => message.error(cause.message),
    })
  }

  const columns: TableProps<Delivery>['columns'] = [
    { title: '投递 ID', dataIndex: 'id', key: 'id', width: 96 },
    { title: '事件', dataIndex: 'eventId', key: 'eventId', width: 96 },
    { title: 'Endpoint', dataIndex: 'endpointId', key: 'endpointId', width: 104 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 112,
      render: (value: DeliveryStatus) => <DeliveryStatusTag status={value} />,
    },
    {
      title: '尝试',
      key: 'attempts',
      width: 88,
      render: (_, delivery) => (
        <span className="tabular-nums">
          {delivery.attemptCount}
          <span className="text-gray-400"> / {delivery.maxAttempts}</span>
        </span>
      ),
    },
    {
      title: '最近错误',
      key: 'lastError',
      render: (_, delivery) =>
        delivery.lastErrorClass === null ? (
          <span className="text-gray-300">—</span>
        ) : (
          <Typography.Text code className="!text-xs">
            {delivery.lastErrorClass}
            {delivery.lastStatusCode !== null && ` · ${delivery.lastStatusCode}`}
          </Typography.Text>
        ),
    },
    {
      title: '下次尝试',
      dataIndex: 'nextAttemptAt',
      key: 'nextAttemptAt',
      width: 180,
      render: (value: string | null) => (
        <span className="tabular-nums">{formatTimestamp(value)}</span>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (value: string) => <span className="tabular-nums">{formatTimestamp(value)}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 96,
      render: (_, delivery) => (
        <Popconfirm
          title="重放这条投递？"
          description="状态回到 PENDING，并额外给一次尝试额度。"
          okText="重放"
          cancelText="取消"
          disabled={delivery.status !== 'DEAD'}
          onConfirm={() => handleReplay(delivery.id)}
        >
          <Button size="small" disabled={delivery.status !== 'DEAD'} loading={replay.isPending}>
            重放
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <PageTitle
        title="投递"
        subtitle="按状态或 Endpoint 过滤 · 需要租户 API Key"
        actions={
          <Space>
            <LiveBadge intervalMs={POLL_INTERVAL_MS.deliveries} fetching={isFetching} />
            <Button icon={<ReloadOutlined />} size="small" onClick={() => void refetch()}>
              刷新
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
          message="读取投递失败"
          description={error.message}
          action={
            <Button size="small" onClick={() => void refetch()}>
              重试
            </Button>
          }
        />
      )}

      <Card styles={{ body: { padding: 0 } }}>
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-100 p-3">
          <Select<DeliveryStatus>
            allowClear
            placeholder="全部状态"
            style={{ width: 180 }}
            value={status}
            onChange={(value) => {
              setStatus(value)
              setPage(0)
            }}
            options={DELIVERY_STATUSES.map((value) => ({
              value,
              label: STATUS_META[value].label,
            }))}
          />
          <InputNumber
            placeholder="Endpoint ID"
            min={1}
            controls={false}
            style={{ width: 160 }}
            value={endpointId}
            onChange={(value) => {
              setEndpointId(value)
              setPage(0)
            }}
          />
          {(status !== undefined || endpointId !== null) && (
            <Button
              type="link"
              size="small"
              onClick={() => {
                setStatus(undefined)
                setEndpointId(null)
                setPage(0)
              }}
            >
              清除筛选
            </Button>
          )}
        </div>

        <Table<Delivery>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={isFetching && data === undefined}
          scroll={{ x: 980 }}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total: data?.total ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setPage(nextPage - 1),
          }}
          locale={{ emptyText: '没有匹配的投递。' }}
        />
      </Card>
    </div>
  )
}
