import { Alert, Button } from 'antd'
import { useUiStore } from '@/stores/uiStore'

/**
 * Shown instead of an empty table when the tenant credential is missing.
 *
 * The distinction matters: an empty table means "no rows", a failed request means
 * "could not ask". Rendering the first when it is the second is how a missing API key
 * gets reported as "the system has no events".
 *
 * Operator pages no longer need this: their credential is the login session, and an
 * expired session is a 401 that `SessionExpiryListener` turns into a trip to the
 * login page.
 */
export function CredentialAlert() {
  const openSettings = useUiStore((state) => state.openSettings)
  const tenantToken = useUiStore((state) => state.tenantToken)

  if (tenantToken !== '') {
    return null
  }

  return (
    <Alert
      className="mb-4"
      type="warning"
      showIcon
      message="尚未配置租户 API Key"
      description="这一页的数据需要 Authorization: Bearer hk_… 才能读取。让管理员在「租户」页建一个租户，或从已有租户那里取一个 Key。"
      action={
        <Button size="small" type="primary" onClick={openSettings}>
          填入凭证
        </Button>
      }
    />
  )
}
