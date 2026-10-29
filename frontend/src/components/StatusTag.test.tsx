import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EventDeliverySummary, DeliveryStatusTag, LiveBadge } from './StatusTag'
import { renderWithProviders } from '@/test/render'
import type { DeliveryStatus, EventSummary } from '@/api/types'

function event(overrides: Partial<EventSummary> = {}): EventSummary {
  return {
    eventId: 1,
    eventType: 'order.paid',
    deliveryCount: 2,
    succeededCount: 1,
    failedCount: 0,
    inFlightCount: 1,
    createdAt: '2026-10-29T08:31:02.114',
    ...overrides,
  }
}

describe('DeliveryStatusTag', () => {
  it.each<[DeliveryStatus, string]>([
    ['PENDING', '待投递'],
    ['DELIVERING', '投递中'],
    ['RETRY_WAIT', '等待重试'],
    ['SUCCEEDED', '成功'],
    ['DEAD', '死信'],
    ['CANCELED', '已取消'],
  ])('labels %s as %s', (status, label) => {
    render(<DeliveryStatusTag status={status} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})

describe('EventDeliverySummary', () => {
  it('renders every non-zero counter, so a split event is neither "done" nor "failed"', () => {
    renderWithProviders(<EventDeliverySummary event={event()} />)
    expect(screen.getByText('1 成功')).toBeInTheDocument()
    expect(screen.getByText('1 进行中')).toBeInTheDocument()
    expect(screen.queryByText(/失败/)).not.toBeInTheDocument()
  })

  it('says so when an event matched no endpoint', () => {
    renderWithProviders(
      <EventDeliverySummary
        event={event({ deliveryCount: 0, succeededCount: 0, inFlightCount: 0 })}
      />,
    )
    expect(screen.getByText('未匹配 Endpoint')).toBeInTheDocument()
  })

  it('shows failures alongside successes rather than hiding one of them', () => {
    renderWithProviders(
      <EventDeliverySummary
        event={event({ deliveryCount: 3, succeededCount: 1, failedCount: 2, inFlightCount: 0 })}
      />,
    )
    expect(screen.getByText('1 成功')).toBeInTheDocument()
    expect(screen.getByText('2 失败')).toBeInTheDocument()
  })
})

describe('LiveBadge', () => {
  it('states the polling interval in seconds', () => {
    render(<LiveBadge intervalMs={2000} fetching={false} />)
    expect(screen.getByText('每 2 秒刷新')).toBeInTheDocument()
  })
})
