/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional build-time default for the tenant API key (`hk_...`). */
  readonly VITE_HOOKFLOW_TOKEN?: string
  /** Optional build-time default for the operator bootstrap token. */
  readonly VITE_HOOKFLOW_BOOTSTRAP_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
