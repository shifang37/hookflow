import { useMemo } from 'react'
import { Alert, Button, Card, Col, Empty, Row, Segmented, Select, Space, Statistic, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ApiError } from '@/api/client'
import { POLL_INTERVAL_MS, useLatency, useOverview, useTimeseries } from '@/api/queries'
import { formatBucketLabel, formatCount, formatLatencyBucket, formatMillis, formatPercent } from '@/api/time'
import { INTERVALS, STATS_RANGES, type Interval, type StatsRange } from '@/api/types'
import { PageTitle } from '@/components/PageTitle'
import { CredentialAlert } from '@/components/CredentialAlert'
import { LiveBadge } from '@/components/StatusTag'
import { CHART_COLORS } from '@/theme'
import { useUiStore } from '@/stores/uiStore'

/**
 * The monitoring page: one window selector drives three requests (overview, timeseries,
 * latency), and the window lives in the shared store so switching pages does not reset
 * what you were looking at.
 *
 * The two charts answer different questions on purpose. The line chart is *rate* — is
 * work arriving and settling. The bar chart is *distribution* — when a delivery is slow,
 * how slow. An average would answer neither.
 */
export function OverviewPage() {
  const range = useUiStore((state) => state.range)
  const updateRange = useUiStore((state) => state.updateRange)
  const interval = useUiStore((state) => state.interval)
  const updateInterval = useUiStore((state) => state.updateInterval)

  const overview = useOverview(range)
  const series = useTimeseries({ range, interval })
  const latency = useLatency({ range })

  const throughput = useMemo(
    () =>
      (series.data?.points ?? []).map((point) => ({
        label: formatBucketLabel(point.bucketStart),
        events: point.events,
        succeeded: point.succeeded,
        dead: point.dead,
      })),
    [series.data],
  )

  const latencyBuckets = useMemo(() => {
    let previous: number | null = null
    return (latency.data?.buckets ?? []).map((bucket) => {
      const label = formatLatencyBucket(bucket.upperBoundMs, previous)
      if (bucket.upperBoundMs !== null) {
        previous = bucket.upperBoundMs
      }
      return { label, count: bucket.count, percentage: bucket.percentage }
    })
  }, [latency.data])

  const error = overview.error ?? series.error ?? latency.error

  return (
    <div>
      <PageTitle
        title="系统监控"
        subtitle="运营域 · 需要 X-Bootstrap-Token · 窗口与前端图表同源，口径见 docs/api.md"
        actions={
          <Space wrap>
            <LiveBadge intervalMs={POLL_INTERVAL_MS.overview} fetching={overview.isFetching} />
            <Select<StatsRange>
              value={range}
              onChange={updateRange}
              style={{ width: 96 }}
              options={STATS_RANGES.map((value) => ({ value, label: value }))}
            />
            <Button
              icon={<ReloadOutlined />}
              size="small"
              onClick={() => {
                void overview.refetch()
                void series.refetch()
                void latency.refetch()
              }}
            >
              刷新
            </Button>
          </Space>
        }
      />

      <CredentialAlert scope="admin" />

      {error !== null && !(error instanceof ApiError && error.status === 401) && (
        <Alert className="mb-4" type="error" showIcon message="读取监控数据失败" description={error.message} />
      )}

      <Row gutter={[12, 12]} className="mb-2">
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="事件数" value={formatCount(overview.data?.totalEvents)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic
              title="成功率"
              value={formatPercent(overview.data?.successRate)}
              valueStyle={{
                color:
                  overview.data === undefined
                    ? undefined
                    : overview.data.successRate >= 95
                      ? '#3f8600'
                      : '#cf1322',
              }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="平均端到端延迟" value={formatMillis(overview.data?.avgLatencyMs)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="当前积压" value={formatCount(overview.data?.backlog)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="活跃租户" value={formatCount(overview.data?.activeTenants)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} lg={4}>
          <Card size="small">
            <Statistic title="活跃 Endpoint" value={formatCount(overview.data?.activeEndpoints)} />
          </Card>
        </Col>
      </Row>

      <Card
        title="吞吐与结算"
        size="small"
        className="mb-2"
        extra={
          <Space>
            <Typography.Text type="secondary" className="!text-xs">
              分桶
            </Typography.Text>
            <Segmented<Interval>
              size="small"
              value={interval}
              onChange={updateInterval}
              options={INTERVALS.map((value) => ({ value, label: value }))}
            />
          </Space>
        }
      >
        {throughput.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="窗口内没有数据" />
        ) : (
          <div className="h-72 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={throughput} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="label" tick={{ fontSize: 11 }} minTickGap={24} />
                <YAxis tick={{ fontSize: 11 }} width={48} allowDecimals={false} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Line
                  type="monotone"
                  dataKey="events"
                  name="事件到达"
                  stroke={CHART_COLORS.events}
                  dot={false}
                  strokeWidth={2}
                />
                <Line
                  type="monotone"
                  dataKey="succeeded"
                  name="投递成功"
                  stroke={CHART_COLORS.succeeded}
                  dot={false}
                  strokeWidth={2}
                />
                <Line
                  type="monotone"
                  dataKey="dead"
                  name="死信"
                  stroke={CHART_COLORS.dead}
                  dot={false}
                  strokeWidth={2}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
        <Typography.Text type="secondary" className="!text-xs">
          到达数按事件创建时间计，成功/死信按结算时间计 —— 所以一次突发会先看到蓝线抬起，再看到绿线跟上。
        </Typography.Text>
      </Card>

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={16}>
          <Card title="单次尝试耗时分布" size="small">
            {latency.data !== undefined && latency.data.samples === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="窗口内没有尝试记录" />
            ) : (
              <div className="h-64 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={latencyBuckets} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 11 }} interval={0} />
                    <YAxis tick={{ fontSize: 11 }} width={48} allowDecimals={false} />
                    <Tooltip />
                    <Bar dataKey="count" name="尝试次数" fill={CHART_COLORS.events} radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title={`近 ${range} 延迟百分位`} size="small">
            <Statistic title="样本数" value={formatCount(latency.data?.samples)} />
            <div className="mt-3 grid grid-cols-3 gap-2 text-center">
              {[
                { label: 'P50', value: latency.data?.p50Ms },
                { label: 'P95', value: latency.data?.p95Ms },
                { label: 'P99', value: latency.data?.p99Ms },
              ].map((item) => (
                <div key={item.label} className="rounded bg-gray-50 py-2">
                  <div className="text-xs text-gray-500">{item.label}</div>
                  <div className="tabular-nums text-sm font-medium">{formatMillis(item.value)}</div>
                </div>
              ))}
            </div>
            <Typography.Paragraph type="secondary" className="!mb-0 !mt-3 !text-xs">
              nearest-rank，返回真实观测值；没有样本时显示 — 而不是 0，免得把「没测到」看成「很快」。
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
