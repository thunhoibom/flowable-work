import type { ModdleElement } from './modeler-types'

/**
 * Read helpers and editor rules for subprocesses and the events the platform supports on them.
 * They mirror the backend SubProcessDeploymentGuard so the designer only offers valid shapes.
 */

export interface MultiInstanceForm {
  enabled: boolean
  sequential: boolean
  collection: string
  elementVariable: string
  completionCondition: string
}

export type TimerKind = 'timeDuration' | 'timeDate' | 'timeCycle'

export interface TimerForm {
  kind: TimerKind
  value: string
}

const VARIABLE = /^[A-Za-z][A-Za-z0-9_]{0,127}$/
const ISO_DURATION = /^P(?!$)(\d+Y)?(\d+M)?(\d+W)?(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?$/
const TIMER_KINDS: TimerKind[] = ['timeDuration', 'timeDate', 'timeCycle']
/** Replace options the backend rejects. Call activities are allowed only once that phase ships. */
const UNSUPPORTED_REPLACEMENTS = /transaction|ad-hoc/
const CALL_ACTIVITY_REPLACEMENT = /call-activity/
const EVENT_SUBPROCESS_STARTS = /-(timer|error)-start$/
const SUBPROCESS_BOUNDARIES = /-(timer|error)-boundary$/

function text(value: unknown) {
  if (!value) return ''
  if (typeof value === 'string') return value
  const expression = value as ModdleElement
  return String(expression.body ?? expression.text ?? '')
}

export function isExpression(value: string) {
  return value.includes('${') || value.includes('#{')
}

export function isEventSubProcess(object?: ModdleElement) {
  return object?.$type === 'bpmn:SubProcess' && object.triggeredByEvent === true
}

export function isEmbeddedSubProcess(object?: ModdleElement) {
  return object?.$type === 'bpmn:SubProcess' && object.triggeredByEvent !== true
}

export function readMultiInstance(object?: ModdleElement): MultiInstanceForm {
  const loop = object?.loopCharacteristics
  if (loop?.$type !== 'bpmn:MultiInstanceLoopCharacteristics') {
    return {
      enabled: false,
      sequential: false,
      collection: '',
      elementVariable: '',
      completionCondition: '',
    }
  }
  return {
    enabled: true,
    sequential: loop.isSequential === true,
    collection: String(loop.collection ?? ''),
    elementVariable: String(loop.elementVariable ?? ''),
    completionCondition: text(loop.completionCondition),
  }
}

export function multiInstanceWarnings(form: MultiInstanceForm) {
  if (!form.enabled) return []
  const warnings: string[] = []
  if (!form.collection.trim()) warnings.push('Cần nhập danh sách để lặp')
  if (!VARIABLE.test(form.elementVariable.trim())) warnings.push('Tên biến phần tử không hợp lệ')
  if (form.completionCondition.trim() && !isExpression(form.completionCondition)) {
    warnings.push('Điều kiện dừng phải là biểu thức ${...}')
  }
  return warnings
}

export function eventKind(object?: ModdleElement): 'timer' | 'error' | undefined {
  const definition = (object?.eventDefinitions as ModdleElement[] | undefined)?.[0]
  if (definition?.$type === 'bpmn:TimerEventDefinition') return 'timer'
  if (definition?.$type === 'bpmn:ErrorEventDefinition') return 'error'
  return undefined
}

export function eventDefinition(object?: ModdleElement) {
  return (object?.eventDefinitions as ModdleElement[] | undefined)?.[0]
}

export function readTimer(definition?: ModdleElement): TimerForm {
  const kind = TIMER_KINDS.find((candidate) => definition?.[candidate]) ?? 'timeDuration'
  return { kind, value: text(definition?.[kind]) }
}

export function timerWarnings(form: TimerForm) {
  const value = form.value.trim()
  if (!value) return ['Cần nhập giá trị hẹn giờ']
  if (isExpression(value)) return []
  if (form.kind === 'timeDuration' && !ISO_DURATION.test(value)) {
    return ['Thời hạn phải theo ISO-8601, ví dụ P3D hoặc PT4H']
  }
  if (form.kind === 'timeCycle' && !value.startsWith('R')) {
    return ['Chu kỳ phải theo ISO-8601, ví dụ R3/PT10M']
  }
  if (form.kind === 'timeDate' && Number.isNaN(Date.parse(value))) {
    return ['Thời điểm phải theo ISO-8601, ví dụ 2026-12-31T17:00:00']
  }
  return []
}

export function readErrorCode(definition?: ModdleElement) {
  const error = definition?.errorRef as ModdleElement | undefined
  return String(error?.errorCode ?? '')
}

/** Walks up to bpmn:Definitions, where shared bpmn:Error elements live. */
export function definitionsOf(object?: ModdleElement) {
  let current: ModdleElement | undefined = object
  while (current && current.$type !== 'bpmn:Definitions') {
    current = current.$parent as ModdleElement | undefined
  }
  return current
}

export interface ReplaceTarget {
  businessObject?: ModdleElement
}

/** Removes replace-menu options the backend would reject for this element. */
export function filterReplaceEntries<T>(
  target: ReplaceTarget,
  entries: Record<string, T>,
  options: { callActivity?: boolean } = {},
): Record<string, T> {
  const object = target.businessObject
  const parent = object?.$parent as ModdleElement | undefined
  const attachedTo = object?.attachedToRef as ModdleElement | undefined
  return Object.fromEntries(
    Object.entries(entries).filter(([id]) => {
      if (UNSUPPORTED_REPLACEMENTS.test(id)) return false
      if (!options.callActivity && CALL_ACTIVITY_REPLACEMENT.test(id)) return false
      if (object?.$type === 'bpmn:StartEvent' && isEventSubProcess(parent)) {
        return EVENT_SUBPROCESS_STARTS.test(id)
      }
      if (object?.$type === 'bpmn:BoundaryEvent' && isEmbeddedSubProcess(attachedTo)) {
        return SUBPROCESS_BOUNDARIES.test(id)
      }
      return true
    }),
  )
}
