export type ProcessVariableType = 'string' | 'number' | 'boolean' | 'json'

export interface ProcessVariableDraft {
  name: string
  type: ProcessVariableType
  value: string
}

const INTERNAL_PARTICIPANT_SUFFIXES = ['_assignee', '_assigneeList', '_candidateGroupList']

export function buildProcessVariables(rows: ProcessVariableDraft[]) {
  const variables: Record<string, unknown> = {}
  for (const row of rows) {
    const name = row.name.trim()
    if (!name && !row.value.trim()) continue
    if (!name) throw new Error('Hãy nhập tên dữ liệu')
    if (INTERNAL_PARTICIPANT_SUFFIXES.some((suffix) => name.endsWith(suffix))) {
      throw new Error(
        'Hãy chọn người trong mục “Giao việc”, không nhập tên biến kỹ thuật',
      )
    }
    if (Object.prototype.hasOwnProperty.call(variables, name)) {
      throw new Error(`Tên dữ liệu bị trùng: ${name}`)
    }
    variables[name] = parseVariableValue(row, name)
  }
  return variables
}

function parseVariableValue(row: ProcessVariableDraft, name: string) {
  switch (row.type) {
    case 'number': {
      const value = Number(row.value)
      if (!row.value.trim() || !Number.isFinite(value)) throw new Error(`${name} phải là số hợp lệ`)
      return value
    }
    case 'boolean':
      return row.value === 'true'
    case 'json':
      try {
        return JSON.parse(row.value) as unknown
      } catch {
        throw new Error(`${name} phải là JSON hợp lệ`)
      }
    default:
      return row.value
  }
}
