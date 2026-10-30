import type { InteractionDataField } from './types'

export type InteractionValues = Record<string, unknown>

export function synchronizeInteractionValues(
  fields: InteractionDataField[],
  current: InteractionValues,
): InteractionValues {
  return Object.fromEntries(
    fields.map((field) => [
      field.variablePath,
      current[field.variablePath] ?? field.currentValue ?? defaultValue(field),
    ]),
  )
}

export function buildInteractionVariables(
  fields: InteractionDataField[],
  values: InteractionValues,
): Record<string, unknown> {
  const variables: Record<string, unknown> = {}
  for (const field of fields) {
    const value = normalizeValue(field, values[field.variablePath])
    if (field.required && isMissing(value)) throw new Error(`Hãy điền “${field.label}”`)
    if (!isMissing(value)) setNestedValue(variables, field.variablePath, value)
  }
  return variables
}

export function mergeInteractionVariables(
  base: Record<string, unknown>,
  generated: Record<string, unknown>,
): Record<string, unknown> {
  return mergeObjects(base, generated)
}

function defaultValue(field: InteractionDataField): unknown {
  if (field.dataType === 'boolean') return undefined
  return ''
}

function normalizeValue(field: InteractionDataField, raw: unknown): unknown {
  if (raw === undefined || raw === null || raw === '') return undefined
  if (field.dataType === 'integer' || field.dataType === 'number') {
    const value = typeof raw === 'number' ? raw : Number(raw)
    if (!Number.isFinite(value)) throw new Error(`“${field.label}” phải là số`)
    if (field.dataType === 'integer' && !Number.isInteger(value)) {
      throw new Error(`“${field.label}” phải là số nguyên`)
    }
    return value
  }
  if (field.dataType === 'boolean') {
    if (typeof raw === 'boolean') return raw
    if (raw === 'true') return true
    if (raw === 'false') return false
    throw new Error(`“${field.label}” phải chọn Có hoặc Không`)
  }
  if (field.dataType === 'object' || field.dataType === 'array') {
    if (typeof raw !== 'string') return raw
    try {
      const value: unknown = JSON.parse(raw)
      const matches = field.dataType === 'array' ? Array.isArray(value) : isPlainObject(value)
      if (!matches) throw new Error()
      return value
    } catch {
      throw new Error(
        `“${field.label}” không phải JSON ${field.dataType === 'array' ? 'mảng' : 'đối tượng'} hợp lệ`,
      )
    }
  }
  return String(raw)
}

function setNestedValue(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path.split('.')
  let cursor = target
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      cursor[segment] = value
      return
    }
    const existing = cursor[segment]
    if (existing !== undefined && !isPlainObject(existing)) {
      throw new Error(`Đường dẫn biến “${path}” xung đột với biến đã có`)
    }
    const child = (existing ?? {}) as Record<string, unknown>
    cursor[segment] = child
    cursor = child
  })
}

function mergeObjects(
  left: Record<string, unknown>,
  right: Record<string, unknown>,
): Record<string, unknown> {
  const result = { ...left }
  Object.entries(right).forEach(([key, value]) => {
    const existing = result[key]
    result[key] =
      isPlainObject(existing) && isPlainObject(value) ? mergeObjects(existing, value) : value
  })
  return result
}

function isMissing(value: unknown) {
  return value === undefined || value === null || (typeof value === 'string' && !value.trim())
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
