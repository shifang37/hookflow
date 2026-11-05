import { useEffect } from 'react'
import { App, Button, Drawer, Form, Input, Space, Typography } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { drawerWidth } from '@/components/drawerWidth'
import { useUiStore } from '@/stores/uiStore'

interface FormValues {
  tenantToken: string
}

/**
 * Where the tenant API key is entered.
 *
 * The operator identity is not edited here: it is a server-side session created
 * by the login page. This drawer only holds the *other* credential — the tenant
 * key that authorizes `/v1/events`, `/v1/deliveries` and `/v1/endpoints`. It is
 * kept in localStorage rather than a build-time constant so a reviewer can point
 * the console at their own tenant without a rebuild, and it is never echoed back
 * into the input once saved, so a shared screen does not leak a key.
 */
export function TokenDrawer() {
  const open = useUiStore((state) => state.settingsOpen)
  const close = useUiStore((state) => state.closeSettings)
  const save = useUiStore((state) => state.saveTenantToken)
  const clear = useUiStore((state) => state.clearCredentials)
  const tenantToken = useUiStore((state) => state.tenantToken)
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ tenantToken })
    }
  }, [open, tenantToken, form])

  return (
    <Drawer
      title="租户凭证"
      open={open}
      onClose={close}
      width={drawerWidth(420)}
      extra={
        <Button
          danger
          size="small"
          onClick={() => {
            clear()
            form.setFieldsValue({ tenantToken: '' })
            void queryClient.invalidateQueries()
            message.success('已清空租户凭证')
          }}
        >
          清空
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" className="!text-xs">
        控制台登录已经用会话保护管理接口；这里填的是<strong>租户</strong> API Key，
        用于事件流、投递和 Endpoint 页面。它只读得到这个租户的数据，不能跨租户。
      </Typography.Paragraph>

      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => {
          save(values.tenantToken ?? '')
          // Every tenant query was failing on the same missing header; re-ask them all.
          void queryClient.invalidateQueries()
          message.success('租户凭证已保存')
          close()
        }}
      >
        <Form.Item label="租户 API Key" name="tenantToken">
          <Input.Password placeholder="hk_xxxxxxxx_yyyy…" autoComplete="off" />
        </Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            保存
          </Button>
          <Button onClick={close}>取消</Button>
        </Space>
      </Form>
    </Drawer>
  )
}
