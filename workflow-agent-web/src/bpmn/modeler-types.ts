export type ApprovalMode = '' | 'single' | 'candidate' | 'parallel'
export type ListenerKind = 'executionStart' | 'executionEnd' | 'taskCreate' | 'taskComplete'

export interface ModdleElement {
  $type?: string
  id?: string
  name?: string
  event?: string
  body?: string
  text?: string
  expression?: string
  class?: string
  delegateExpression?: string
  values?: ModdleElement[]
  $attrs?: Record<string, unknown>
  documentation?: ModdleElement[]
  extensionElements?: ModdleElement
  conditionExpression?: ModdleElement | string
  sourceRef?: ModdleElement
  targetRef?: ModdleElement
  loopCharacteristics?: ModdleElement
  [key: string]: unknown
}

export interface BpmnElement {
  id?: string
  type?: string
  businessObject?: ModdleElement
}

export interface BpmnReplace {
  replaceElement(element: BpmnElement, properties: { type: string }): BpmnElement
}

export interface BpmnEvent {
  newSelection?: BpmnElement[]
  element?: BpmnElement
  originalEvent?: MouseEvent
  [key: string]: unknown
}

export interface Modeling {
  updateModdleProperties(
    element: BpmnElement,
    businessObject: ModdleElement,
    properties: Record<string, unknown>,
  ): void
  updateProperties(element: BpmnElement, properties: Record<string, unknown>): void
}

export interface ElementRegistry {
  get(id: string): BpmnElement | undefined
  getAll(): BpmnElement[]
  updateId?(element: BpmnElement, id: string): void
}

export interface Moddle {
  create(type: string, properties?: Record<string, unknown>): ModdleElement
}

export interface Canvas {
  addMarker(elementId: string, marker: string): void
  removeMarker(elementId: string, marker: string): void
  resized(): void
  zoom(scale: string | number, position?: string): void
  getRootElement(): BpmnElement
}

export interface BpmnModelerInstance {
  destroy(): void
  get(serviceName: string): unknown
  importXML(xml: string): Promise<void>
  on(eventName: string, callback: (event: BpmnEvent) => void): void
  saveXML(options: { format: boolean }): Promise<{ xml: string }>
}
