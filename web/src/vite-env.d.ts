/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the read API (public host, not a secret). Empty => relative/same-origin. */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
