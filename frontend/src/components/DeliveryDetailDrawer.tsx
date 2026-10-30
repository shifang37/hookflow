import { Alert, Descriptions, Drawer, Empty, Spin, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useDeliveryDetail } from '@/api/queries'
import { formatMillis, formatTimestamp } from '@/api/time'
import type { DeliveryAttempt } from '@/api/types'
import { drawerWidth } from '@/components/drawerWidth'
import { DeliveryStatusTag } from '@/components/StatusTag'

const ATTEMPT_COLORS: Record<string, string> = {
  SUCCEEDED: 'green',
  IN_PROGRESS: 'blue',
  FAILED: 'red',
  ABANDONED: 'orange',
}

const ATTEMPT_COLUMNS: TableProps<DeliveryAttempt>['columns'] = [
  { title: '尝试', dataIndex: 'attemptNo', key: 'attemptNo', width: 64 },
  {
    title: '结果',
    dataIndex: 'status',
    key: 'status',
    width: 112,
    render: (value: string) => <Tag color={ATTEMPT_COLORS[value] ?? 'default'}>{value}</Tag>,
  },
  {
    title: 'HTTP',
    dataIndex: 'statusCode',
    key: 'statusCode',
    width: 72,
    render: (value: number | null) => (value === null ? '—' : value),
  },
  {
    title: '耗时',
    dataIndex: 'durationMs',
    key: 'durationMs',
    width: 88,
    render: (value: number | null) => <span className="tabular-nums">{formatMillis(value)}</span>,
  },
  {
    title: '错误',
    dataIndex: 'errorClass',
    key: 'errorClass',
    width: 132,
    render: (value: string | null) =>
      value === null ? (
        <span className="text-gray-300">—</span>
      ) : (
        <Typography.Text code className="!text-xs">
          {value}
        </Typography.Text>
      ),
  },
  {
    title: '开始',
    dataIndex: 'startedAt',
    key: 'startedAt',
    width: 168,
    render: (value: string) => <span className="tabular-nums">{formatTimestamp(value)}</span>,
  },
  {
    title: '结束',
    dataIndex: 'finishedAt',
    key: 'finishedAt',
    width: 168,
    render: (value: string | null) => <span className="tabular-nums">{formatTimestamp(value)}</span>,
  },
]

/**
 * The per-delivery view: the state machine's current state plus every attempt it took.
 *
 * The list page answers "which deliveries are in trouble"; this drawer answers "what did
 * the receiver actually return on attempt 3". The response body is only a truncated
 * preview (the worker stores at most `maxResponsePreviewBytes`), so the expansion shows
 * exactly what was kept, not a promise of the full response.
 */
export function DeliveryDetailDrawer({
  deliveryId,
  onClose,
}: {
  deliveryId: number | undefined
  onClose: () => void
}) {
  const { data, error } = useDeliveryDetail(deliveryId)

  return (
    <Drawer
      title={data === undefined ? '投递' : `投递 #${data.delivery.id}`}
      open={deliveryId !== undefined}
      onClose={onClose}
      width={drawerWidth(900)}
      destroyOnHidden
    >
      {error !== null && (
        <Alert type="error" showIcon message="读取投递详情失败" description={error.message} />
      )}

      {data === undefined ? (
        <Spin />
      ) : (
        <div className="space-y-4">
          <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
            <Descriptions.Item label="投递 ID">{data.delivery.id}</Descriptions.Item>
            <Descriptions.Item label="事件 ID">{data.delivery.eventId}</Descriptions.Item>
            <Descriptions.Item label="Endpoint ID">{data.delivery.endpointId}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <DeliveryStatusTag status={data.delivery.status} />
            </Descriptions.Item>
            <Descriptions.Item label="尝试次数">
              {data.delivery.attemptCount} / {data.delivery.maxAttempts}
            </Descriptions.Item>
            <Descriptions.Item label="下次尝试">
              {formatTimestamp(data.delivery.nextAttemptAt)}
            </Descriptions.Item>
            <Descriptions.Item label="最近错误">
              {data.delivery.lastErrorClass === null ? (
                '—'
              ) : (
                <Typography.Text code>
                  {data.delivery.lastErrorClass}
                  {data.delivery.lastStatusCode !== null && ` · ${data.delivery.lastStatusCode}`}
                </Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {formatTimestamp(data.delivery.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">
              {formatTimestamp(data.delivery.updatedAt)}
            </Descriptions.Item>
          </Descriptions>

          <div>
            <div className="mb-2 text-sm font-medium">尝试历史（{data.attempts.length}）</div>
            {data.attempts.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="还没有尝试记录。投递尚未被 worker 领取。"
              />
            ) : (
              <Table<DeliveryAttempt>
                rowKey="attemptNo"
                size="small"
                pagination={false}
                columns={ATTEMPT_COLUMNS}
                dataSource={data.attempts}
                scroll={{ x: 760 }}
                expandable={{
                  expandedRowRender: (attempt) =>
                    attempt.responsePreview === null ? (
                      <span className="text-gray-500">没有响应体（超时或连接失败）。</span>
                    ) : (
                      <pre className="max-h-48 overflow-auto rounded bg-gray-50 p-2 text-xs">
                        {attempt.responsePreview}
                      </pre>
                    ),
                  rowExpandable: (attempt) =>
                    attempt.responsePreview !== null || attempt.durationMs === null,
                }}
              />
            )}
          </div>
        </div>
      )}
    </Drawer>
  )
}
