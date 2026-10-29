import { Alert, Button, Input, Modal, Typography } from 'antd'

/**
 * The one place in the console where a secret is shown.
 *
 * Shared by endpoint creation (signing secret) and key issuance (API key) because both
 * have the same hard rule: the server keeps only a hash or a ciphertext, so this dialog
 * is the *only* copy the user will ever see. That makes it deliberately hard to dismiss
 * — `maskClosable` and `keyboard` are off, and there is no close icon, so leaving
 * requires pressing the acknowledgement button.
 */
export function SecretRevealModal({
  open,
  title,
  alertMessage,
  description,
  value,
  valueLabel,
  subject,
  onClose,
}: {
  open: boolean
  title: string
  /** One line, imperative: what the user loses if they dismiss this. */
  alertMessage: string
  description: string
  value: string
  /** `aria-label` for the read-only field; also what a test should look for. */
  valueLabel: string
  /** Context line above the field, e.g. `#1 · https://…`. */
  subject?: string | undefined
  onClose: () => void
}) {
  return (
    <Modal
      title={title}
      open={open}
      maskClosable={false}
      keyboard={false}
      closable={false}
      width={560}
      footer={[
        <Button key="done" type="primary" onClick={onClose}>
          我已保存
        </Button>,
      ]}
    >
      <Alert type="warning" showIcon className="mb-3" message={alertMessage} description={description} />
      {subject !== undefined && (
        <Typography.Paragraph className="!mb-1 text-xs text-gray-500">{subject}</Typography.Paragraph>
      )}
      <Input.TextArea
        readOnly
        autoSize={{ minRows: 2, maxRows: 3 }}
        value={value}
        className="!font-mono !text-xs"
        aria-label={valueLabel}
      />
      <Typography.Paragraph copyable={{ text: value }} className="!mb-0 !mt-2 !text-xs">
        点右侧图标复制。
      </Typography.Paragraph>
    </Modal>
  )
}
