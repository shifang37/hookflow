import { useMemo } from 'react'
import { Alert, Descriptions, Drawer, Spin, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useEventDetail } from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import type { Delivery } from '@/api/types'
import { DeliveryStatusTag } from '@/components/StatusTag'

const COLUMNS: TableProps<Delivery>['columns'] = [
  { title: '投递', dataIndex: 'id', key: 'id', width: 80 },
  { title: 'Endpoint', dataIndex: 'endpointId', key: 'endpointId', width: 92 },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 104,
    render: (_, delivery) => <DeliveryStatusTag status={delivery.status} />,
  },
  {
    title: '尝试',
    key: 'attempts',
    width: 84,
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
]

/**
 * Pretty-print only. The payload arrives as the raw JSON text the platform stored, and the
 * console must not pretend it understands it better than it does: an unparseable payload is
 * shown verbatim with a note, not hidden and not "fixed".
 */
function prettyPayload(raw: string): { text: string; parsed: boolean } {
  try {
    return { text: JSON.stringify(JSON.parse(raw), null, 2), parsed: true }
  } catch {
    return { text: raw, parsed: false }
  }
}

/**
 * The event drawer: what was accepted, and where it went.
 *
 * The delivery list is the fan-out of this one event — one row per endpoint that matched at
 * ingest time — which is the question an operator actually has when a customer says "we did
 * not receive it": did it fan out at all, and to whom.
 */
export function EventDetailDrawer({
  eventId,
  onClose,
}: {
  eventId: number | undefined
  onClose: () => void
}) {
  const { data, error } = useEventDetail(eventId)
  const payload = useMemo(
    () => (data === undefined ? undefined : prettyPayload(data.payload)),
    [data],
  )

  return (
    <Drawer
      title={data === undefined ? '事件' : `事件 #${data.eventId}`}
      open={eventId !== undefined}
      onClose={onClose}
      width={760}
      destroyOnHidden
    >
      {error !== null && (
        <Alert type="error" showIcon message="读取事件失败" description={error.message} />
      )}

      {data === undefined ? (
        <Spin />
      ) : (
        <div className="space-y-4">
          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label="Event ID">{data.eventId}</Descriptions.Item>
            <Descriptions.Item label="事件类型">
              <Typography.Text code>{data.eventType}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">{formatTimestamp(data.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="投递数">{data.deliveryCount}</Descriptions.Item>
          </Descriptions>

          <div>
            <div className="mb-2 text-sm font-medium">Payload</div>
            <pre className="max-h-80 overflow-auto rounded bg-gray-50 p-3 text-xs">
              {payload?.text}
            </pre>
            {payload !== undefined && !payload.parsed && (
              <div className="mt-1 text-xs text-gray-500">不是合法 JSON，按原文显示。</div>
            )}
          </div>

          <div>
            <div className="mb-2 text-sm font-medium">
              投递（{data.deliveries.length}
              {data.deliveryCount > data.deliveries.length ? ` / 共 ${data.deliveryCount}` : ''}）
            </div>
            <Table<Delivery>
              rowKey="id"
              size="small"
              pagination={false}
              columns={COLUMNS}
              dataSource={data.deliveries}
              locale={{ emptyText: '没有匹配的 Endpoint，因此没有产生投递。' }}
            />
          </div>
        </div>
      )}
    </Drawer>
  )
}
