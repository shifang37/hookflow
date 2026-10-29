import { App, Form, Input, InputNumber, Modal } from 'antd'
import { useCreateTenant } from '@/api/queries'
import type { TenantProvisioned } from '@/api/types'

interface CreateTenantValues {
  name: string
  rateLimit: number
  maxInFlight: number
  weight: number
}

/** Mirrors the backend defaults in {@code AdminController.provisionTenant}. */
const DEFAULT_RATE_LIMIT = 100
const DEFAULT_MAX_IN_FLIGHT = 32

/**
 * Provisioning a tenant: the last operation that used to require curl.
 *
 * <p>The form mirrors the server's constraints (name ≤ 64 and unique, rate limit and
 * budget ≥ 0, weight 1–100) but does not try to be authoritative — a duplicate name is
 * still caught by {@code uk_tenant_name} and comes back as a 409 whose message is shown
 * verbatim. Client-side rules only exist to keep that round trip out of obvious mistakes.
 */
export function CreateTenantModal({
  open,
  onCancel,
  onCreated,
}: {
  open: boolean
  onCancel: () => void
  onCreated: (created: TenantProvisioned) => void
}) {
  const [form] = Form.useForm<CreateTenantValues>()
  const create = useCreateTenant()
  const { message } = App.useApp()

  return (
    <Modal
      title="创建租户"
      open={open}
      onCancel={() => {
        form.resetFields()
        onCancel()
      }}
      onOk={() => form.submit()}
      okText="创建"
      cancelText="取消"
      confirmLoading={create.isPending}
      destroyOnHidden
      width={560}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          rateLimit: DEFAULT_RATE_LIMIT,
          maxInFlight: DEFAULT_MAX_IN_FLIGHT,
          weight: 1,
        }}
        onFinish={(values) =>
          create.mutate(
            {
              name: values.name.trim(),
              rateLimit: values.rateLimit,
              maxInFlight: values.maxInFlight,
              weight: values.weight,
            },
            {
              onSuccess: (created) => {
                form.resetFields()
                onCreated(created)
              },
              onError: (cause) => message.error(cause.message),
            },
          )
        }
      >
        <Form.Item
          label="租户名称"
          name="name"
          rules={[
            { required: true, message: '请输入租户名称' },
            { max: 64, message: '最多 64 个字符' },
          ]}
          extra="唯一。重名会返回 409，不会建出第二个租户。"
        >
          <Input placeholder="acme" />
        </Form.Item>

        <div className="grid grid-cols-3 gap-3">
          <Form.Item
            label="限流（事件/秒）"
            name="rateLimit"
            rules={[{ required: true, message: '必填' }]}
            extra="0 = 不限速"
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="并发预算"
            name="maxInFlight"
            tooltip="该租户同时在投递中的条数上限，决定它能排在别人前面多少条"
            rules={[{ required: true, message: '必填' }]}
            extra="0 = 不限"
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="公平权重"
            name="weight"
            tooltip="竞争时的加权轮询份额，不放大吞吐"
            rules={[{ required: true, message: '必填' }]}
            extra="1–100"
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </div>
      </Form>
    </Modal>
  )
}
