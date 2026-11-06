export interface ScriptInputRow {
  target: string
  source: string
}

export interface FlowableInParameter {
  target: string
  source?: string
  sourceExpression?: string
}

const VARIABLE = /^[A-Za-z][A-Za-z0-9_]{0,127}$/
const TARGET = /^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/

export function isExpression(value: string) {
  return value.includes('${') || value.includes('#{')
}

export function toInParameters(rows: ScriptInputRow[]): FlowableInParameter[] {
  return rows
    .map((row) => ({ target: row.target.trim(), source: row.source.trim() }))
    .filter((row) => row.target || row.source)
    .map((row) =>
      isExpression(row.source)
        ? { target: row.target, sourceExpression: row.source }
        : { target: row.target, source: row.source },
    )
}

export function fromInParameters(
  values: Array<{ target?: unknown; source?: unknown; sourceExpression?: unknown }>,
): ScriptInputRow[] {
  return values.map((value) => ({
    target: String(value.target || ''),
    source: String(value.sourceExpression || value.source || ''),
  }))
}

export function scriptTaskWarnings(
  script: string,
  rows: ScriptInputRow[],
  resultVariable: string,
): string[] {
  const warnings: string[] = []
  if (!script.trim()) warnings.push('Script đang trống')
  const seen = new Set<string>()
  for (const row of toInParameters(rows)) {
    if (!TARGET.test(row.target)) {
      warnings.push(`Tên đầu vào không hợp lệ: ${row.target || '(trống)'}`)
    } else if (seen.has(row.target)) {
      warnings.push(`Tên đầu vào bị trùng: ${row.target}`)
    }
    seen.add(row.target)
    if (!row.sourceExpression && !VARIABLE.test(row.source || '')) {
      warnings.push(`Nguồn không hợp lệ cho ${row.target}`)
    }
  }
  const result = resultVariable.trim()
  if (result && !VARIABLE.test(result)) warnings.push(`Tên biến kết quả không hợp lệ: ${result}`)
  return warnings
}

export function isAutomaticTask(type: string) {
  return type === 'bpmn:ServiceTask' || type === 'bpmn:ScriptTask'
}
