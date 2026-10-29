import { Alert, Button, Space } from 'antd'
import { useUiStore } from '@/stores/uiStore'

/**
 * Shown instead of an empty table when the credential for a whole trust domain is
 * missing.
 *
 * The distinction matters: an empty table means "no rows", a failed request means
 * "could not ask". Rendering the first when it is the second is how a missing API key
 * gets reported as "the system has no events".
 */
export function CredentialAlert({ scope }: { scope: 'tenant' | 'admin' }) {
  const openSettings = useUiStore((state) => state.openSettings)
  const tenantToken = useUiStore((state) => state.tenantToken)
  const adminToken = useUiStore((state) => state.adminToken)
  const missing = scope === 'tenant' ? tenantToken === '' : adminToken === ''

  if (!missing) {
    return null
  }

  return (
    <Alert
      className="mb-4"
      type="warning"
      showIcon
      message={scope === 'tenant' ? '尚未配置租户 API Key' : '尚未配置运营令牌'}
      description={
        scope === 'tenant'
          ? '这一页的数据需要 Authorization: Bearer hk_… 才能读取。用 POST /v1/admin/tenants 建一个租户，或从已有租户那里取一个 Key。'
          : '这一页的数据需要 X-Bootstrap-Token 才能读取，值和后端 hookflow.admin.bootstrap-token 一致。'
      }
      action={
        <Space>
          <Button size="small" type="primary" onClick={openSettings}>
            填入凭证
          </Button>
        </Space>
      }
    />
  )
}
