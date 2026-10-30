import { useState } from 'react'
import { Alert, Descriptions, Input, Modal, Typography } from 'antd'
import { useTestEndpoint } from '@/api/queries'
import type { Endpoint, EndpointTestResult } from '@/api/types'

/**
 * Sends one synthetic webhook and shows the receiver's answer.
 *
 * This is a probe, not an ingest: the backend writes no event, delivery or attempt row.
 * That is why it is safe to put next to "pause"/"delete" on a production callback — the
 * operator can verify the URL and signing setup before resuming without creating a fake
 * event that every dashboard would then count.
 */
export function EndpointTestModal({
  endpoint,
  onClose,
}: {
  endpoint: Endpoint
  onClose: () => void
}) {
  const [payloadText, setPayloadText] = useState('')
  const [result, setResult] = useState<EndpointTestResult | undefined>(undefined)
  const [error, setError] = useState<string | undefined>(undefined)
  const test = useTestEndpoint()

  const send = () => {
    const trimmed = payloadText.trim()
    let payload: Record<string, unknown> | undefined

    if (trimmed !== '') {
      let parsed: unknown
      try {
        parsed = JSON.parse(trimmed)
      } catch {
        setError('payload 必须是合法 JSON')
        return
      }
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        setError('payload 必须是一个 JSON 对象，例如 {"orderId":123}')
        return
      }
      payload = parsed as Record<string, unknown>
    }

    setError(undefined)
    setResult(undefined)
    test.mutate(
      { endpointId: endpoint.id, payload },
      {
        onSuccess: setResult,
        onError: (cause) => setError(cause.message),
      },
    )
  }

  return (
    <Modal
      title={`测试 Endpoint #${endpoint.id}`}
      open
      onCancel={onClose}
      onOk={send}
      okText="发送测试"
      cancelText="关闭"
      confirmLoading={test.isPending}
      destroyOnHidden
      width={640}
    >
      <div className="space-y-4">
        <Alert
          type="info"
          showIcon
          message="会立即向该 Endpoint 发送一条合成 webhook"
          description="使用真实的签名密钥，事件类型为 endpoint.test；事件、投递和 attempt 都不会入库。"
        />

        <div>
          <div className="mb-1 text-sm font-medium">Payload（可选，必须是 JSON 对象）</div>
          <Input.TextArea
            rows={4}
            value={payloadText}
            onChange={(event) => setPayloadText(event.target.value)}
            placeholder={'留空发送 {}\n例如：{"orderId":123,"amount":99}'}
            className="font-mono"
          />
        </div>

        {error !== undefined && (
          <Alert type="error" showIcon message="测试失败" description={error} />
        )}

        {result !== undefined && (
          <div className="space-y-3">
            <Alert
              type={result.success ? 'success' : 'error'}
              showIcon
              message={
                result.success
                  ? `接收方接受（HTTP ${result.statusCode}，${result.durationMs} ms）`
                  : `接收方未接受（${result.statusCode ?? result.errorClass ?? '无响应'}，${result.durationMs} ms）`
              }
              description={
                result.retryable
                  ? '按投递分类这是可重试错误（408 / 429 / 5xx / 网络错误）。'
                  : '按投递分类这是不可重试结果。'
              }
            />
            <Descriptions size="small" column={{ xs: 1, sm: 3 }} bordered>
              <Descriptions.Item label="HTTP">
                {result.statusCode ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="耗时">{result.durationMs} ms</Descriptions.Item>
              <Descriptions.Item label="错误分类">
                {result.errorClass === null ? '—' : result.errorClass}
              </Descriptions.Item>
            </Descriptions>
            <div>
              <div className="mb-1 text-sm font-medium">响应预览</div>
              <pre className="max-h-48 overflow-auto rounded bg-gray-50 p-2 text-xs">
                {result.responsePreview ?? '没有响应体（超时或连接失败）。'}
              </pre>
            </div>
            <Typography.Text type="secondary" className="text-xs">
              这是测试请求的响应；真实投递的响应预览也会按同样方式截断。
            </Typography.Text>
          </div>
        )}
      </div>
    </Modal>
  )
}
