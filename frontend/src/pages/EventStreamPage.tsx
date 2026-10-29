import { useMemo, useState } from 'react'
import { Alert, Button, Card, Input, Space, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { blankToUndefined, useEvents, POLL_INTERVAL_MS, type EventQuery } from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import type { EventSummary } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { EventDeliverySummary, LiveBadge } from '@/components/StatusTag'

const PAGE_SIZE = 20

/**
 * The live event stream — the page that proves the whole read path end to end:
 * tenant credential → `/v1/events` → one row per event with its delivery roll-up.
 *
 * Polling, not WebSocket: the backend has no push endpoint, and a 2s poll of one
 * paginated query is both simpler and cheaper than maintaining subscriptions for
 * every open browser.
 */
export function EventStreamPage() {
  const [eventType, setEventType] = useState('')
  const [page, setPage] = useState(0)

  const query = useMemo<EventQuery>(
    () => ({ eventType: blankToUndefined(eventType), page, size: PAGE_SIZE }),
    [eventType, page],
  )
  const { data, error, isFetching, refetch } = useEvents(query)

  const columns: TableProps<EventSummary>['columns'] = [
    { title: 'Event ID', dataIndex: 'eventId', key: 'eventId', width: 96 },
    {
      title: '事件类型',
      dataIndex: 'eventType',
      key: 'eventType',
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '投递状态',
      key: 'deliveries',
      render: (_, event) => <EventDeliverySummary event={event} />,
    },
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
        title="事件流"
        subtitle="按 id 倒序，每行带该事件的投递汇总 · 需要租户 API Key"
        actions={
          <Space>
            <LiveBadge intervalMs={POLL_INTERVAL_MS.eventStream} fetching={isFetching} />
            <Button icon={<ReloadOutlined />} onClick={() => void refetch()} size="small">
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
          message="读取事件失败"
          description={
            <span>
              {error.message}
              {error instanceof ApiError && error.requestId !== undefined && (
                <span className="ml-2 text-xs text-gray-500">requestId: {error.requestId}</span>
              )}
            </span>
          }
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
            placeholder="按事件类型精确过滤，如 order.paid"
            style={{ maxWidth: 320 }}
            onSearch={(value) => {
              setEventType(value)
              setPage(0)
            }}
          />
        </div>

        <Table<EventSummary>
          rowKey="eventId"
          size="middle"
          columns={columns}
          dataSource={data?.items ?? []}
          // Only the first load blanks the table; a poll keeps the old rows visible
          // (placeholderData) so the page does not flicker every two seconds.
          loading={isFetching && data === undefined}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total: data?.total ?? 0,
            showSizeChanger: false,
            onChange: (nextPage) => setPage(nextPage - 1),
          }}
          locale={{ emptyText: '还没有事件。用 POST /v1/events 提交一条试试。' }}
        />
      </Card>
    </div>
  )
}
