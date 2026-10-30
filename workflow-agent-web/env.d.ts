/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_API_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module 'bpmn-js/lib/Modeler' {
  const Modeler: new (
    options: Record<string, unknown>,
  ) => import('./src/bpmn/modeler-types').BpmnModelerInstance
  export default Modeler
}

declare module 'bpmn-js/lib/NavigatedViewer' {
  const Viewer: new (
    options: Record<string, unknown>,
  ) => import('./src/bpmn/modeler-types').BpmnModelerInstance
  export default Viewer
}
