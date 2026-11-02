import type { CatalogOperation, SaveOperationCommand } from './api'
import { pathRequirement } from './parameterDraft'

export interface ParameterInputRow {
  name: string
  type: string
  required: boolean
  location: string
}

export interface ParameterOutputRow {
  name: string
  type: string
  jsonField: string
}

export interface ErrorRuleRow {
  patterns: string
  bpmnErrorCode: string
}

export interface OperationDraft {
  operationKey: string
  method: string
  url: string
  authType: string
  /** INHERIT uses the service secret. OVERRIDE uses the fields below. NONE sends nothing. */
  authMode: string
  apiKeyHeader: string
  secret: string
  bodyTemplate: string
  timeoutMillis: number
  connectTimeoutMillis: number
  errorRules: ErrorRuleRow[]
  successStatuses: string
  errorStatusCodes: string
  bpmnErrorCode: string
  inputs: ParameterInputRow[]
  outputs: ParameterOutputRow[]
}

export const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const
export const authTypes = [
  { value: 'NONE', label: 'Không' },
  { value: 'BEARER', label: 'Bearer' },
  { value: 'BASIC', label: 'Basic' },
  { value: 'API_KEY', label: 'API Key' },
] as const
export const authModes = [
  { value: 'INHERIT', label: 'Dùng chung của dịch vụ' },
  { value: 'OVERRIDE', label: 'Riêng thao tác này' },
  { value: 'NONE', label: 'Không xác thực' },
] as const
export const parameterTypes = ['STRING', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON'] as const
export const parameterLocations = ['PATH', 'QUERY', 'HEADER', 'BODY'] as const

export function blankOperationDraft(): OperationDraft {
  return {
    operationKey: '',
    method: 'POST',
    url: '',
    authType: 'NONE',
    // A new operation uses the service credentials until the author chooses otherwise.
    authMode: 'INHERIT',
    apiKeyHeader: '',
    secret: '',
    bodyTemplate: '',
    timeoutMillis: 5000,
    connectTimeoutMillis: 5000,
    errorRules: [{ patterns: '409,5XX', bpmnErrorCode: 'HTTP_ERROR' }],
    successStatuses: '',
    errorStatusCodes: '409,5XX',
    bpmnErrorCode: 'HTTP_ERROR',
    inputs: [],
    outputs: [],
  }
}

export function draftFromOperation(operation: CatalogOperation): OperationDraft {
  const legacyPatterns = operation.errorStatusCodes.join(',')
  const errorRules = operation.errorRules?.length
    ? operation.errorRules.map((rule) => ({
        patterns: rule.patterns.join(','),
        bpmnErrorCode: rule.bpmnErrorCode,
      }))
    : [{ patterns: legacyPatterns, bpmnErrorCode: operation.bpmnErrorCode }]
  return {
    operationKey: operation.operationKey,
    method: operation.method,
    url: operation.urlTemplate,
    authType: operation.authType,
    // No stored mode means this operation was saved before inheritance existed.
    authMode: operation.authMode || 'OVERRIDE',
    apiKeyHeader: operation.apiKeyHeader || '',
    secret: '',
    bodyTemplate: operation.bodyTemplate || '',
    timeoutMillis: operation.timeoutMillis,
    connectTimeoutMillis: operation.connectTimeoutMillis ?? operation.timeoutMillis,
    errorRules,
    successStatuses: (operation.successStatuses || []).join(','),
    errorStatusCodes: legacyPatterns,
    bpmnErrorCode: operation.bpmnErrorCode,
    inputs: (operation.inputParameters || []).map((row) => ({
      name: row.name,
      type: row.type,
      required: pathRequirement(row.location, row.required).required,
      location: row.location,
    })),
    outputs: (operation.outputParameters || []).map((row) => ({ ...row })),
  }
}

export function isOperationDraftDirty(current: OperationDraft, baseline: OperationDraft) {
  return JSON.stringify(snapshot(current)) !== JSON.stringify(snapshot(baseline))
}

export function validateOperationDraft(draft: OperationDraft): string | null {
  if (!draft.operationKey.trim()) return 'Thiếu mã thao tác'
  if (!draft.url.trim()) return 'Thiếu mẫu địa chỉ'
  if (!Number.isInteger(draft.timeoutMillis) || draft.timeoutMillis < 1) {
    return 'Thời gian chờ phải từ 1 mili giây'
  }
  if (!Number.isInteger(draft.connectTimeoutMillis) || draft.connectTimeoutMillis < 1) {
    return 'Thời gian kết nối phải từ 1 mili giây'
  }
  // The header belongs to this operation only when it overrides the shared credentials.
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'API_KEY' && !draft.apiKeyHeader.trim()) {
    return 'Thiếu header API key'
  }
  if (draft.inputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu vào'
  if (draft.outputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu ra'
  if (draft.outputs.some((row) => !row.jsonField.trim())) return 'Thiếu trường JSON đầu ra'
  return null
}

function splitPatterns(value: string) {
  return value
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean)
}

export function toSaveCommand(draft: OperationDraft): SaveOperationCommand {
  const errorRules = draft.errorRules.map((rule) => ({
    patterns: splitPatterns(rule.patterns),
    bpmnErrorCode: rule.bpmnErrorCode.trim() || 'HTTP_ERROR',
  }))
  const firstRule = errorRules[0]
  return {
    method: draft.method,
    url: draft.url.trim(),
    authType: draft.authType,
    authMode: draft.authMode,
    apiKeyHeader: draft.apiKeyHeader.trim() || undefined,
    secret: draft.secret || undefined,
    bodyTemplate: draft.bodyTemplate || undefined,
    timeoutMillis: draft.timeoutMillis,
    connectTimeoutMillis: draft.connectTimeoutMillis,
    errorRules,
    successStatuses: draft.successStatuses,
    // The first rule is also written to the old columns so a published snapshot stays readable.
    errorStatusCodes: firstRule ? firstRule.patterns : splitPatterns(draft.errorStatusCodes),
    bpmnErrorCode: firstRule?.bpmnErrorCode || draft.bpmnErrorCode.trim() || 'HTTP_ERROR',
    inputParameters: draft.inputs.map((row) => ({
      name: row.name.trim(),
      type: row.type,
      required: pathRequirement(row.location, row.required).required,
      location: row.location,
    })),
    outputParameters: draft.outputs.map((row) => ({
      name: row.name.trim(),
      type: row.type,
      jsonField: row.jsonField.trim(),
    })),
  }
}

function snapshot(draft: OperationDraft) {
  return {
    operationKey: draft.operationKey,
    method: draft.method,
    url: draft.url,
    authType: draft.authType,
    apiKeyHeader: draft.apiKeyHeader,
    secret: draft.secret,
    bodyTemplate: draft.bodyTemplate,
    authMode: draft.authMode,
    timeoutMillis: draft.timeoutMillis,
    connectTimeoutMillis: draft.connectTimeoutMillis,
    errorRules: draft.errorRules.map((rule) => ({
      patterns: rule.patterns,
      bpmnErrorCode: rule.bpmnErrorCode,
    })),
    successStatuses: draft.successStatuses,
    errorStatusCodes: draft.errorStatusCodes,
    bpmnErrorCode: draft.bpmnErrorCode,
    inputs: draft.inputs.map((row) => ({
      name: row.name,
      type: row.type,
      required: row.required,
      location: row.location,
    })),
    outputs: draft.outputs.map((row) => ({
      name: row.name,
      type: row.type,
      jsonField: row.jsonField,
    })),
  }
}
