/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional build-time default for the tenant API key (`hk_...`). */
  readonly VITE_HOOKFLOW_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
