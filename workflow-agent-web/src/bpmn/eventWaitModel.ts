import { isReservedVariable } from '@/features/process-definition/callActivityPanel'
import type { Moddle, ModdleElement } from './modeler-types'

/**
 * Read, write and check helpers for steps that wait for an Event Registry event: a receive task
 * with flowable:eventType, or an intermediate catch / boundary event with no event definition and
 * flowable:eventType. The problems mirror the backend EventRegistryDeploymentGuard.
 */

export type EventWaitKind = 'receiveTask' | 'intermediateCatch' | 'boundary'

export interface EventWaitForm {
  eventKey: string
  correlations: { name: string; value: string }[]
  outputs: { source: string; target: string; transient: boolean }[]
}

/** The part of an event model the wait needs. EventModelView fits. */
export interface EventWaitEvent {
  key: string
  name?: string
  description?: string | null
  fields: { name: string; correlation: boolean; type?: string }[]
  publishedFields?: { name: string; correlation: boolean; type?: string }[] | null
}

const EVENT_TYPE = 'flowable:EventType'
const CORRELATION = 'flowable:EventCorrelationParameter'
const OUTPUT = 'flowable:EventOutParameter'
const EVENT_EXTENSIONS = new Set([EVENT_TYPE, CORRELATION, OUTPUT])
const EVENT_KEY = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/
const VARIABLE = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/

function values(object?: ModdleElement) {
  return object?.extensionElements?.values ?? []
}

function hasEventType(object?: ModdleElement) {
  return values(object).some((value) => value.$type === EVENT_TYPE)
}

function hasEventDefinition(object?: ModdleElement) {
  return ((object?.eventDefinitions as ModdleElement[] | undefined) ?? []).length > 0
}

/** Intermediate/boundary event with no event definition: the shape the event mode can take. */
export function canBeEventWait(object?: ModdleElement) {
  return (
    (object?.$type === 'bpmn:IntermediateCatchEvent' || object?.$type === 'bpmn:BoundaryEvent') &&
    !hasEventDefinition(object)
  )
}

/** Receive task with eventType, or intermediate/boundary event with no event definition and eventType. */
export function eventWaitKind(object?: ModdleElement): EventWaitKind | undefined {
  if (!hasEventType(object)) return undefined
  if (object?.$type === 'bpmn:ReceiveTask') return 'receiveTask'
  if (!canBeEventWait(object)) return undefined
  return object?.$type === 'bpmn:BoundaryEvent' ? 'boundary' : 'intermediateCatch'
}

export function readEventWait(object?: ModdleElement): EventWaitForm {
  const extensions = values(object)
  const type = extensions.find((value) => value.$type === EVENT_TYPE)
  return {
    eventKey: String(type?.body ?? '').trim(),
    correlations: extensions
      .filter((value) => value.$type === CORRELATION)
      .map((value) => ({ name: String(value.name ?? ''), value: String(value.value ?? '') })),
    outputs: extensions
      .filter((value) => value.$type === OUTPUT)
      .map((value) => ({
        source: String(value.source ?? ''),
        target: String(value.target ?? ''),
        transient: value.transient === true,
      })),
  }
}

function filledOutputs(form: EventWaitForm) {
  return form.outputs.filter((output) => output.source.trim() || output.target.trim())
}

/**
 * Extension values for the form. Keeps every extension that is not eventType,
 * eventCorrelationParameter or eventOutParameter (execution listeners, other plugins).
 */
export function eventWaitExtensions(
  moddle: Moddle,
  current: ModdleElement[],
  form: EventWaitForm,
): ModdleElement[] {
  return [
    moddle.create(EVENT_TYPE, { body: form.eventKey.trim() }),
    ...form.correlations.map((correlation) =>
      moddle.create(CORRELATION, { name: correlation.name, value: correlation.value.trim() }),
    ),
    ...filledOutputs(form).map((output) =>
      moddle.create(OUTPUT, {
        source: output.source.trim(),
        target: output.target.trim(),
        ...(output.transient ? { transient: true } : {}),
      }),
    ),
    ...withoutEventExtensions(current),
  ]
}

/** Removes the event extensions: used when switching a receive task back to manual. */
export function withoutEventExtensions(current: ModdleElement[]): ModdleElement[] {
  return current.filter((value) => !EVENT_EXTENSIONS.has(String(value.$type)))
}

/** The published fields when there are some, else the draft's. */
export function eventFields(event?: EventWaitEvent) {
  return event?.publishedFields ?? event?.fields ?? []
}

export function correlationFields(event?: EventWaitEvent) {
  return eventFields(event)
    .filter((field) => field.correlation)
    .map((field) => field.name)
}

/** Mirrors EventRegistryDeploymentGuard rows 6–10, without the "Bước X:" prefix. */
export function eventWaitProblems(form: EventWaitForm, event?: EventWaitEvent): string[] {
  const key = form.eventKey.trim()
  if (!key) return ['Chưa chọn sự kiện']
  if (key.includes('${') || key.includes('#{') || !EVENT_KEY.test(key)) {
    return ['Mã sự kiện phải là mã cố định hợp lệ']
  }
  if (!event) return [`Sự kiện ${key} chưa được phát hành`]
  const problems: string[] = []
  if (form.correlations.length === 0) problems.push('Phải có ít nhất một trường tương quan')
  const correlatable = new Set(correlationFields(event))
  const all = new Set(eventFields(event).map((field) => field.name))
  const seen = new Set<string>()
  for (const correlation of form.correlations) {
    if (!correlatable.has(correlation.name)) {
      problems.push(`Sự kiện ${key} không có trường tương quan ${correlation.name}`)
    } else if (seen.has(correlation.name)) {
      problems.push(`Trường tương quan ${correlation.name} bị lặp`)
    } else if (!correlation.value.trim()) {
      problems.push(`Trường tương quan ${correlation.name} chưa có giá trị`)
    }
    seen.add(correlation.name)
  }
  const targets = new Set<string>()
  for (const output of filledOutputs(form)) {
    const source = output.source.trim()
    const target = output.target.trim()
    if (!all.has(source)) problems.push(`Sự kiện ${key} không có trường ${source}`)
    if (!VARIABLE.test(target)) {
      problems.push(`Tên biến ${target} không hợp lệ`)
    } else if (isReservedVariable(target)) {
      problems.push(`Biến ${target} do hệ thống quản lý`)
    } else if (targets.has(target)) {
      problems.push(`Biến ${target} được gán hai lần`)
    }
    targets.add(target)
  }
  return problems
}

function mentions(value: string, variable: string) {
  return new RegExp(`(^|[^A-Za-z0-9_])${variable}([^A-Za-z0-9_]|$)`).test(value)
}

export function eventWaitWarnings(input: {
  form: EventWaitForm
  event?: EventWaitEvent
  /** R-B5 */
  multiInstanceElementVariable?: string
  asyncLeave: boolean
  /** Receive task only. */
  hasTimeout?: boolean
}): string[] {
  const warnings: string[] = []
  const used = new Set(input.form.correlations.map((correlation) => correlation.name))
  for (const name of correlationFields(input.event)) {
    if (!used.has(name)) {
      warnings.push(
        `Trường tương quan ${name} của sự kiện không được dùng. Mọi sự kiện có cùng các trường còn lại đều khớp`,
      )
    }
  }
  const variable = input.multiInstanceElementVariable?.trim()
  if (
    variable &&
    input.form.correlations.some((correlation) => !mentions(correlation.value, variable))
  ) {
    warnings.push(
      `Trong bước lặp, giá trị tương quan nên dùng biến phần tử \${${variable}}, nếu không mọi lượt lặp sẽ cùng nhận một sự kiện`,
    )
  }
  if (input.hasTimeout === false) {
    warnings.push('Bước chờ chưa có hạn chờ. Công việc có thể chờ mãi nếu sự kiện không đến.')
  }
  if (!input.asyncLeave) {
    warnings.push(
      'Đã tắt "Rời bước bất đồng bộ": lỗi ở các bước phía sau sẽ làm việc nhận sự kiện thất bại theo.',
    )
  }
  return warnings
}
