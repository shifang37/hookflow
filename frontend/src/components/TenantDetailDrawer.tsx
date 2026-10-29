import { Alert, App, Button, Card, Descriptions, Drawer, Empty, Form, InputNumber, List, Statistic, Typography } from 'antd'
import { useTenantStats, useUpdateTenant } from '@/api/queries'
import { formatCount, formatMillis, formatPercent, formatTimestamp } from '@/api/time'
import type { Tenant } from '@/api/types'
import { TenantKeysCard } from '@/components/TenantKeysCard'
import { TenantStatusTag } from '@/components/StatusTag'
import { useUiStore } from '@/stores/uiStore'

/**
 * The drawer is where a tenant's numbers and its policy meet — the four statistics are
 * what an operator looks at, the three inputs are what they change in response. Keeping
 * them on one screen is the difference between "the tenant is at 60% failure" and
 * "…and here is the knob".
 */
export function TenantDetailDrawer({
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
