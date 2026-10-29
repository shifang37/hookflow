import { useState } from 'react'
import { Alert, App, Button, Card, Empty, List, Popconfirm, Space, Tag, Typography } from 'antd'
import { useIssueTenantKey, useRevokeApiKey, useTenantKeys } from '@/api/queries'
import { formatTimestamp } from '@/api/time'
import type { IssuedKey, Tenant } from '@/api/types'
import { SecretRevealModal } from '@/components/SecretRevealModal'

/**
 * Key rotation, inside the tenant drawer.
 *
 * Three states are deliberately visible at once: the live key (prefix + scopes), the
 * revoked ones (so "I rotated it" is provable after the fact), and the exact time each
 * happened. The plaintext never appears here — issuing it opens a separate dialog that
 * shows it once, because the server keeps only a hash and this list could not show it
 * even if the UI wanted to.
 */
export function TenantKeysCard({ tenant }: { tenant: Tenant }) {
  const { message } = App.useApp()
  const { data: keys, error } = useTenantKeys(tenant.tenantId)
  const issue = useIssueTenantKey(tenant.tenantId)
  const revoke = useRevokeApiKey()
  const [issued, setIssued] = useState<IssuedKey | undefined>(undefined)

  return (
    <Card
      size="small"
      title="API Keys"
      extra={
        <Button
          size="small"
          type="primary"
          loading={issue.isPending}
          onClick={() =>
            issue.mutate(undefined, {
              onSuccess: (key) => setIssued(key),
              onError: (cause) => message.error(cause.message),
            })
          }
        >
          生成新 Key
        </Button>
      }
    >
      {error !== null && <Alert type="error" showIcon message="读取 API Key 失败" description={error.message} />}

      {keys !== undefined && keys.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有 API Key" />
      ) : (
        <List
          size="small"
          dataSource={keys ?? []}
          renderItem={(key) => (
            <List.Item
              actions={[
                <Popconfirm
                  key="revoke"
                  title="撤销这把 Key？"
                  description="撤销后立刻失效：用它发出的请求会开始返回 401，且不可恢复。"
                  okText="撤销"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  disabled={key.status === 'REVOKED'}
                  onConfirm={() =>
                    revoke.mutate(key.id, {
                      onSuccess: () => message.success('已撤销'),
                      onError: (cause) => message.error(cause.message),
                    })
                  }
                >
                  <Button size="small" danger disabled={key.status === 'REVOKED'}>
                    撤销
                  </Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                title={
                  <Space size={6}>
                    <Typography.Text code className="!text-xs">
                      hk_{key.keyPrefix}_…
                    </Typography.Text>
                    {key.status === 'ACTIVE' ? (
                      <Tag color="success">有效</Tag>
                    ) : (
                      <Tag>已撤销</Tag>
                    )}
                  </Space>
                }
                description={
                  <span className="text-xs text-gray-500">
                    {key.scopes} · 创建 {formatTimestamp(key.createdAt)}
                    {key.revokedAt !== null && ` · 撤销 ${formatTimestamp(key.revokedAt)}`}
                  </span>
                }
              />
            </List.Item>
          )}
        />
      )}

      <SecretRevealModal
        open={issued !== undefined}
        title="API Key 已生成"
        alertMessage="API Key 只显示这一次"
        description="服务端只存 SHA-256 哈希，之后任何接口都不会再返回明文。现在不保存，就只能撤销后重建一把。"
        value={issued?.apiKey ?? ''}
        valueLabel="api key"
        subject={issued === undefined ? undefined : `${tenant.name} · 前缀 ${issued.keyPrefix}`}
        onClose={() => setIssued(undefined)}
      />
    </Card>
  )
}
