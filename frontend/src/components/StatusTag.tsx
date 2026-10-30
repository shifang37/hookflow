import { Badge, Space, Tag, Tooltip } from 'antd'
import type { DeliveryStatus, EndpointStatus, EventSummary, TenantStatus } from '@/api/types'

/**
 * Status vocabulary in one place.
 *
 * The backend's enum is the contract; these labels and colours are the only place the
 * UI decides what a state *looks* like. Anything colour-coded elsewhere (charts, cards)
 * must reference the same map, so "red" cannot mean DEAD on one screen and CANCELED on
 * another.
 */
export const STATUS_META: Record<DeliveryStatus, { label: string; color: string }> = {
  PENDING: { label: '待投递', color: 'default' },
  DELIVERING: { label: '投递中', color: 'processing' },
  RETRY_WAIT: { label: '等待重试', color: 'warning' },
  SUCCEEDED: { label: '成功', color: 'success' },
  DEAD: { label: '死信', color: 'error' },
  CANCELED: { label: '已取消', color: 'default' },
}

export function DeliveryStatusTag({ status }: { status: DeliveryStatus }) {
  const meta = STATUS_META[status]
  return (
    <Tag color={meta.color} className="!mr-0">
      {meta.label}
    </Tag>
  )
}

/**
 * One event's delivery roll-up, compressed into a single cell.
 *
 * Rendered as counts rather than a single "delivered/pending" bit because an event can
 * be split: two endpoints, one succeeded, one still retrying is neither "done" nor
 * "failed", and the table has no room for a second row to say so.
 */
export function EventDeliverySummary({ event }: { event: EventSummary }) {
  if (event.deliveryCount === 0) {
    return <span className="text-gray-400">未匹配 Endpoint</span>
  }
  return (
    <Space size={4} wrap>
      {event.succeededCount > 0 && (
        <Tooltip title="已成功">
          <Tag color="success" className="!mr-0">
            {event.succeededCount} 成功
          </Tag>
        </Tooltip>
      )}
      {event.failedCount > 0 && (
        <Tooltip title="死信或已取消">
          <Tag color="error" className="!mr-0">
            {event.failedCount} 失败
          </Tag>
        </Tooltip>
      )}
      {event.inFlightCount > 0 && (
        <Tooltip title="待投递 / 投递中 / 等待重试">
          <Tag color="processing" className="!mr-0">
            {event.inFlightCount} 进行中
          </Tag>
        </Tooltip>
      )}
    </Space>
  )
}

/** Header badge: says whether the page is live-polling, not whether it is "healthy". */
export function LiveBadge({ intervalMs, fetching }: { intervalMs: number; fetching: boolean }) {
  return (
    <Badge
      status={fetching ? 'processing' : 'default'}
      text={<span className="text-xs text-gray-500">每 {Math.round(intervalMs / 1000)} 秒刷新</span>}
    />
  )
}

/**
 * Paused is amber, not grey.
 *
 * A paused endpoint still accepts events (they are just not fanned out to it) and its
 * queued deliveries were cancelled when it was paused. That is an operation someone
 * performed and may need to undo, not an inactive row to be de-emphasised.
 */
export function EndpointStatusTag({ status }: { status: EndpointStatus }) {
  return status === 'ACTIVE' ? (
    <Tag color="success">启用</Tag>
  ) : (
    <Tag color="warning">已暂停</Tag>
  )
}

/**
 * Suspended is red rather than grey: it is an operator action with an enforcement
 * consequence, not just an inactive row. `PATCH /v1/admin/tenants/{id}` sets it, and
 * `TenantService.requireActive` rejects that tenant's event submissions with a 403
 * until it is reactivated.
 */
export function TenantStatusTag({ status }: { status: TenantStatus }) {
  return status === 'ACTIVE' ? <Tag color="success">启用</Tag> : <Tag color="error">停用</Tag>
}
