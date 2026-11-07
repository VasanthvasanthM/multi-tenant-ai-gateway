/// <reference types="vite/client" />

// Declares import.meta.env. Without it, `import.meta.env.VITE_API_KEY` is a
// type error - which is the strict-mode behaviour working: nothing should be
// able to read an undeclared environment variable and get `any` back.
interface ImportMetaEnv {
  readonly VITE_GATEWAY_URL?: string;
  readonly VITE_API_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
