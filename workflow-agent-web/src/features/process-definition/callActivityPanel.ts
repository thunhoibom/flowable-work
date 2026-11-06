import type { ModdleElement } from '@/bpmn/modeler-types'

/** One `<flowable:in>` or `<flowable:out>` row. A source with `${` or `#{` is an expression. */
export interface IoRow {
  source: string
  target: string
}

export type BusinessKeyMode = 'none' | 'inherit' | 'expression'

export interface CallActivityForm {
  calledElement: string
  businessKeyMode: BusinessKeyMode
  businessKey: string
  idVariableName: string
  inRows: IoRow[]
  outRows: IoRow[]
}

export interface IoParameter {
  target: string
  source?: string
  sourceExpression?: string
}

const VARIABLE = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const RESERVED_NAMES = new Set(['tenantId', 'tenantCode'])
const RESERVED_SUFFIXES = ['_assignee', '_assigneeList', '_candidateGroupList']

function isExpression(value: string) {
  return value.includes('${') || value.includes('#{')
}

export function isReservedVariable(name: string) {
  return RESERVED_NAMES.has(name) || RESERVED_SUFFIXES.some((suffix) => name.endsWith(suffix))
}

export function toParameters(rows: IoRow[]): IoParameter[] {
  return rows
    .map((row) => ({ source: row.source.trim(), target: row.target.trim() }))
    .filter((row) => row.source || row.target)
    .map((row) =>
      isExpression(row.source)
        ? { sourceExpression: row.source, target: row.target }
        : { source: row.source, target: row.target },
    )
}

function rows(object: ModdleElement | undefined, type: string): IoRow[] {
  return (object?.extensionElements?.values ?? [])
    .filter((item) => item.$type === type)
    .map((item) => ({
      source: String(item.sourceExpression ?? item.source ?? ''),
      target: String(item.target ?? ''),
    }))
}

export function readCallActivity(object?: ModdleElement): CallActivityForm {
  const businessKey = String(object?.businessKey ?? '')
  return {
    calledElement: String(object?.calledElement ?? ''),
    businessKeyMode: businessKey ? 'expression' : object?.inheritBusinessKey ? 'inherit' : 'none',
    businessKey,
    idVariableName: String(object?.idVariableName ?? ''),
    inRows: rows(object, 'flowable:In'),
    outRows: rows(object, 'flowable:Out'),
  }
}

function directionWarnings(label: string, values: IoRow[]) {
  const warnings: string[] = []
  const seen = new Set<string>()
  for (const row of values) {
    const target = row.target.trim()
    const source = row.source.trim()
    if (!target && !source) continue
    if (!VARIABLE.test(target)) {
      warnings.push(`${label}: tên biến "${target}" không hợp lệ`)
      continue
    }
    if (isReservedVariable(target)) warnings.push(`${label}: ${target} do hệ thống quản lý`)
    else if (seen.has(target)) warnings.push(`${label}: ${target} bị trùng`)
    else if (!source) warnings.push(`${label}: dòng ${target} thiếu nguồn`)
    seen.add(target)
  }
  return warnings
}

/** Mirrors CallActivityDeploymentGuard, so problems show before publishing. */
export function callActivityWarnings(form: CallActivityForm) {
  const warnings: string[] = []
  if (!form.calledElement.trim()) warnings.push('Hãy chọn quy trình được gọi')
  if (form.businessKeyMode === 'expression' && !form.businessKey.trim()) {
    warnings.push('Cần nhập biểu thức business key')
  }
  warnings.push(...directionWarnings('Dữ liệu truyền vào', form.inRows))
  warnings.push(...directionWarnings('Dữ liệu nhận về', form.outRows))
  if (form.idVariableName.trim() && !VARIABLE.test(form.idVariableName.trim())) {
    warnings.push('Tên biến lưu id instance con không hợp lệ')
  }
  return warnings
}

/**
 * Required start inputs of the called process that no in-row targets yet. A nested path such
 * as `order.amount` maps the top-level variable `order`.
 */
export function suggestedInRows(
  fields: Array<{ variablePath: string; label?: string; required: boolean }>,
  existing: IoRow[],
): IoRow[] {
  const mapped = new Set(existing.map((row) => row.target.trim()))
  const result: IoRow[] = []
  for (const field of fields) {
    const name = field.variablePath.split('.')[0]!.trim()
    if (!field.required || !name || mapped.has(name)) continue
    mapped.add(name)
    result.push({ source: name, target: name })
  }
  return result
}
