import { useMemo, useState } from 'react'
import { Alert, App, Button, Card, Input, Popconfirm, Space, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  blankToUndefined,
  fetchEventDetail,
  POLL_INTERVAL_MS,
  useBatchReplayDeliveries,
  useEvents,
  type EventQuery,
} from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import { MAX_BATCH_REPLAY, type EventSummary } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { EventDetailDrawer } from '@/components/EventDetailDrawer'
import { describeBatchReplay } from '@/components/replayMessage'
import { EventDeliverySummary, LiveBadge } from '@/components/StatusTag'

const PAGE_SIZE = 20

/**
 * The live event stream — the page that proves the whole read path end to end:
 * tenant credential → `/v1/events` → one row per event with its delivery roll-up.
 *
 * Polling, not WebSocket: the backend has no push endpoint, and a 2s poll of one
 * paginated query is both simpler and cheaper than maintaining subscriptions for
 * every open browser.
 *
 * Batch replay starts from events because that is how an operator thinks ("this order
 * event never arrived"), but the backend's replay unit is a delivery. The selection is
 * therefore resolved through the event detail endpoint at click time: one request per
 * selected event, then one batch call with the DEAD delivery ids it found.
 */
export function EventStreamPage() {
  const { message } = App.useApp()
  const [eventType, setEventType] = useState('')
  const [page, setPage] = useState(0)
  const [selectedEventId, setSelectedEventId] = useState<number | undefined>(undefined)
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([])

  const query = useMemo<EventQuery>(
    () => ({ eventType: blankToUndefined(eventType), page, size: PAGE_SIZE }),
    [eventType, page],
  )
  const { data, error, isFetching, refetch } = useEvents(query)
  const batchReplay = useBatchReplayDeliveries()

  const handleBatchReplay = async () => {
    try {
      const details = await Promise.all(selectedRowKeys.map((eventId) => fetchEventDetail(eventId)))
      const deadDeliveryIds = details.flatMap((detail) =>
        detail.deliveries
          .filter((delivery) => delivery.status === 'DEAD')
          .map((delivery) => delivery.id),
      )
      if (deadDeliveryIds.length === 0) {
        message.info('选中的事件没有死信投递')
        return
      }
      if (deadDeliveryIds.length > MAX_BATCH_REPLAY) {
        message.warning(`一次最多重放 ${MAX_BATCH_REPLAY} 条投递，请减少选择`)
        return
      }
      batchReplay.mutate(deadDeliveryIds, {
        onSuccess: (result) => {
          setSelectedRowKeys([])
          if (result.rejected === 0) {
            message.success(describeBatchReplay(result))
          } else {
            message.warning(describeBatchReplay(result))
          }
        },
        onError: (cause) => message.error(cause.message),
      })
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '读取事件详情失败')
    }
  }

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
        subtitle="按 id 倒序，每行带该事件的投递汇总 · 点一行看 payload 与投递明细 · 需要租户 API Key"
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
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-100 p-3">
          <Input.Search
            allowClear
            placeholder="按事件类型精确过滤，如 order.paid"
            style={{ maxWidth: 320 }}
            onSearch={(value) => {
              setEventType(value)
              setPage(0)
            }}
          />
          {selectedRowKeys.length > 0 && (
            <Popconfirm
              title={`重放选中的 ${selectedRowKeys.length} 个事件？`}
              description="把这些事件下所有死信投递各重放一次（每次一条额外尝试额度）；没有死信的事件会被跳过。"
              okText="确认重放"
              cancelText="取消"
              onConfirm={() => void handleBatchReplay()}
            >
              <Button size="small" type="primary" loading={batchReplay.isPending}>
                批量重放（{selectedRowKeys.length}）
              </Button>
            </Popconfirm>
          )}
        </div>

        <Table<EventSummary>
          rowKey="eventId"
          size="middle"
          columns={columns}
          dataSource={data?.items ?? []}
          // Only the first load blanks the table; a poll keeps the old rows visible
          // (placeholderData) so the page does not flicker every two seconds.
          loading={isFetching && data === undefined}
          rowSelection={{
            selectedRowKeys,
            onChange: (keys) => setSelectedRowKeys(keys.map(Number)),
          }}
          onRow={(event) => ({
            onClick: (click) => {
              // The checkbox cell lives inside the row; opening the drawer from it would
              // make selecting a row for the batch action close the table's focus.
              if ((click.target as HTMLElement).closest('.ant-table-selection-column') !== null) {
                return
              }
              setSelectedEventId(event.eventId)
            },
            style: { cursor: 'pointer' },
          })}
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

      <EventDetailDrawer
        eventId={selectedEventId}
        onClose={() => setSelectedEventId(undefined)}
      />
    </div>
  )
}
