import { useEffect } from 'react'
import { App, Button, Drawer, Form, Input, Space, Typography } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { drawerWidth } from '@/components/drawerWidth'
import { useUiStore } from '@/stores/uiStore'

interface FormValues {
  tenantToken: string
  adminToken: string
}

/**
 * Where the two credentials are entered.
 *
 * Kept in localStorage rather than in a build-time constant so a reviewer can point the
 * console at their own tenant without a rebuild — and never echoed back into the input
 * once saved, so a shared screen does not leak a key. Saving invalidates every query
 * because each of them was probably failing for the same reason.
 */
export function TokenDrawer() {
  const open = useUiStore((state) => state.settingsOpen)
  const close = useUiStore((state) => state.closeSettings)
  const save = useUiStore((state) => state.saveCredentials)
  const clear = useUiStore((state) => state.clearCredentials)
  const tenantToken = useUiStore((state) => state.tenantToken)
  const adminToken = useUiStore((state) => state.adminToken)
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ tenantToken, adminToken })
    }
  }, [open, tenantToken, adminToken, form])

  return (
    <Drawer
      title="凭证"
      open={open}
      onClose={close}
      width={drawerWidth(420)}
      extra={
        <Button
          danger
          size="small"
          onClick={() => {
            clear()
            form.setFieldsValue({ tenantToken: '', adminToken: '' })
            void queryClient.invalidateQueries()
            message.success('已清空凭证')
          }}
        >
          清空
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" className="!text-xs">
        两个信任域，各用各的凭证：<br />
        租户 Key 走 <code>Authorization: Bearer hk_…</code>，只读得到本租户的数据；<br />
        运营令牌走 <code>X-Bootstrap-Token</code>，能跨租户读取统计。
      </Typography.Paragraph>

      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => {
          save({ tenantToken: values.tenantToken ?? '', adminToken: values.adminToken ?? '' })
          // Every query was failing on the same missing header; re-ask them all.
          void queryClient.invalidateQueries()
          message.success('凭证已保存')
          close()
        }}
      >
        <Form.Item label="租户 API Key" name="tenantToken">
          <Input.Password placeholder="hk_xxxxxxxx_yyyy…" autoComplete="off" />
        </Form.Item>
        <Form.Item label="运营令牌（Bootstrap Token）" name="adminToken">
          <Input.Password placeholder="与 hookflow.admin.bootstrap-token 一致" autoComplete="off" />
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
