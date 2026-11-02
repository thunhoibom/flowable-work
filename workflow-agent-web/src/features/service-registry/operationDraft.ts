import type { CatalogOperation, SaveOperationCommand } from './api'
import { pathRequirement } from './parameterDraft'

export interface ParameterInputRow {
  name: string
  type: string
  required: boolean
  location: string
  partKind?: string
}

export interface StaticHeaderRow {
  name: string
  value: string
}

export interface ParameterOutputRow {
  name: string
  type: string
  jsonField: string
  source?: string
  headerName?: string
}

export interface ErrorRuleRow {
  patterns: string
  bpmnErrorCode: string
}

export interface OperationDraft {
  operationKey: string
  method: string
  url: string
  contentType: string
  rawMediaType: string
  staticHeaders: StaticHeaderRow[]
  bodyPresence: string
  apiKeyLocation: string
  bearerVariable: string
  tokenUrl: string
  clientId: string
  scopes: string
  certificate: string
  signatureHeader: string
  accessKey: string
  region: string
  awsService: string
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
  pagination: string
  nextField: string
  nextTokenParameter: string
  maxPages: number
  callStyle: string
  correlationOutput: string
  waitTimeoutMillis: number
}

export const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const
export const authTypes = [
  { value: 'NONE', label: 'Không' },
  { value: 'BEARER', label: 'Bearer' },
  { value: 'BASIC', label: 'Basic' },
  { value: 'API_KEY', label: 'API Key' },
  { value: 'OAUTH2_CLIENT', label: 'OAuth2 client' },
  { value: 'MTLS', label: 'mTLS' },
  { value: 'BEARER_VARIABLE', label: 'Bearer từ biến' },
  { value: 'HMAC', label: 'HMAC' },
  { value: 'AWS_SIG_V4', label: 'AWS SigV4' },
] as const
export const contentTypes = [
  { value: 'JSON', label: 'JSON' },
  { value: 'FORM', label: 'Form' },
  { value: 'MULTIPART', label: 'Multipart' },
  { value: 'RAW', label: 'Thô' },
] as const
export const bodyPresences = [
  { value: 'CONTENT', label: 'Có nội dung' },
  { value: 'EMPTY', label: 'Body rỗng' },
  { value: 'NONE', label: 'Không gửi body' },
] as const
export const apiKeyLocations = [
  { value: 'HEADER', label: 'Header' },
  { value: 'QUERY', label: 'Query' },
] as const
export const partKinds = [
  { value: 'TEXT', label: 'Chữ' },
  { value: 'FILE', label: 'Tệp' },
] as const
export const authModes = [
  { value: 'INHERIT', label: 'Dùng chung của dịch vụ' },
  { value: 'OVERRIDE', label: 'Riêng thao tác này' },
  { value: 'NONE', label: 'Không xác thực' },
] as const
export const parameterTypes = ['STRING', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON'] as const
export const outputSources = [
  { value: 'JSON_FIELD', label: 'Trường JSON' },
  { value: 'RAW_BODY', label: 'Nội dung thô' },
  { value: 'STATUS', label: 'Mã trạng thái' },
  { value: 'RESPONSE_HEADER', label: 'Header phản hồi' },
] as const
export const parameterLocations = ['PATH', 'QUERY', 'HEADER', 'BODY'] as const
export const paginationModes = [
  { value: 'NONE', label: 'Không phân trang' },
  { value: 'NEXT_URL', label: 'URL trang sau' },
  { value: 'NEXT_TOKEN', label: 'Token trang sau' },
] as const
export const callStyles = [
  { value: 'SYNC', label: 'Đồng bộ' },
  { value: 'CALLBACK', label: 'Chờ webhook' },
] as const

export function blankOperationDraft(): OperationDraft {
  return {
    operationKey: '',
    method: 'POST',
    url: '',
    contentType: 'JSON',
    rawMediaType: '',
    staticHeaders: [],
    bodyPresence: 'CONTENT',
    apiKeyLocation: 'HEADER',
    bearerVariable: '',
    tokenUrl: '',
    clientId: '',
    scopes: '',
    certificate: '',
    signatureHeader: '',
    accessKey: '',
    region: '',
    awsService: '',
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
    pagination: 'NONE',
    nextField: '',
    nextTokenParameter: '',
    maxPages: 10,
    callStyle: 'SYNC',
    correlationOutput: '',
    waitTimeoutMillis: 0,
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
    contentType: operation.contentType || 'JSON',
    rawMediaType: operation.rawMediaType || '',
    staticHeaders: (operation.staticHeaders || []).map((header) => ({ ...header })),
    bodyPresence: operation.bodyPresence || 'CONTENT',
    apiKeyLocation: operation.apiKeyLocation || 'HEADER',
    bearerVariable: operation.bearerVariable || '',
    tokenUrl: operation.tokenUrl || '',
    clientId: operation.clientId || '',
    scopes: (operation.scopes || []).join(','),
    certificate: '',
    signatureHeader: operation.signatureHeader || '',
    accessKey: '',
    region: operation.region || '',
    awsService: operation.awsService || '',
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
      partKind: row.partKind || 'TEXT',
    })),
    outputs: (operation.outputParameters || []).map((row) => ({
      ...row,
      source: row.source || 'JSON_FIELD',
      headerName: row.headerName || '',
      jsonField: row.jsonField || '',
    })),
    pagination: operation.steps?.pagination || 'NONE',
    nextField: operation.steps?.nextField || '',
    nextTokenParameter: operation.steps?.nextTokenParameter || '',
    maxPages: operation.steps?.maxPages || 10,
    callStyle: operation.steps?.callStyle || 'SYNC',
    correlationOutput: operation.steps?.correlationOutput || '',
    waitTimeoutMillis: operation.steps?.waitTimeoutMillis || 0,
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
    return draft.apiKeyLocation === 'QUERY' ? 'Thiếu tên query API key' : 'Thiếu header API key'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'BEARER_VARIABLE' && !draft.bearerVariable.trim()) {
    return 'Thiếu biến Bearer'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'OAUTH2_CLIENT' && !draft.tokenUrl.trim()) {
    return 'Thiếu URL token'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'OAUTH2_CLIENT' && !draft.clientId.trim()) {
    return 'Thiếu client id'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'HMAC' && !draft.signatureHeader.trim()) {
    return 'Thiếu header chữ ký'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'AWS_SIG_V4' && !draft.region.trim()) {
    return 'Thiếu region'
  }
  if (draft.authMode === 'OVERRIDE' && draft.authType === 'AWS_SIG_V4' && !draft.awsService.trim()) {
    return 'Thiếu tên service AWS'
  }
  if (draft.contentType === 'RAW' && !draft.rawMediaType.trim()) return 'Thiếu media type'
  if (draft.contentType === 'RAW' && draft.inputs.some((row) => row.location === 'BODY')) {
    return 'Nội dung thô không nhận tham số body'
  }
  if (draft.staticHeaders.some((header) => !header.name.trim())) return 'Thiếu tên header'
  if (draft.inputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu vào'
  if (draft.outputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu ra'
  if (draft.outputs.some((row) => (row.source || 'JSON_FIELD') === 'JSON_FIELD' && !row.jsonField.trim())) {
    return 'Thiếu trường JSON đầu ra'
  }
  if (draft.outputs.some((row) => row.source === 'RESPONSE_HEADER' && !row.headerName?.trim())) {
    return 'Thiếu header phản hồi'
  }
  if (!Number.isInteger(draft.maxPages) || draft.maxPages < 1 || draft.maxPages > 100) {
    return 'Số trang tối đa từ 1 đến 100'
  }
  if (draft.pagination !== 'NONE' && !draft.nextField.trim()) return 'Thiếu trường JSON trang sau'
  if (draft.pagination === 'NEXT_TOKEN' && !draft.nextTokenParameter.trim()) return 'Thiếu tham số nhận token'
  if (
    draft.pagination === 'NEXT_TOKEN' &&
    !draft.inputs.some((row) => row.name.trim() === draft.nextTokenParameter.trim())
  ) {
    return 'Tham số nhận token không có trong đầu vào'
  }
  if (draft.callStyle === 'CALLBACK' && !draft.correlationOutput.trim()) return 'Thiếu đầu ra mã đối soát'
  if (
    draft.callStyle === 'CALLBACK' &&
    !draft.outputs.some((row) => row.name.trim() === draft.correlationOutput.trim())
  ) {
    return 'Đầu ra mã đối soát không có trong đầu ra'
  }
  if (draft.callStyle === 'CALLBACK' && (!Number.isInteger(draft.waitTimeoutMillis) || draft.waitTimeoutMillis < 1)) {
    return 'Thiếu thời gian chờ webhook'
  }
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
    contentType: draft.contentType,
    rawMediaType: draft.rawMediaType.trim() || undefined,
    staticHeaders: draft.staticHeaders.map((header) => ({
      name: header.name.trim(),
      value: header.value,
    })),
    bodyPresence: draft.bodyPresence,
    apiKeyLocation: draft.apiKeyLocation,
    bearerVariable: draft.bearerVariable.trim(),
    tokenUrl: draft.tokenUrl.trim(),
    clientId: draft.clientId.trim(),
    scopes: draft.scopes.trim(),
    certificate: draft.certificate,
    signatureHeader: draft.signatureHeader.trim(),
    accessKey: draft.accessKey,
    region: draft.region.trim(),
    awsService: draft.awsService.trim(),
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
      partKind: row.partKind || 'TEXT',
    })),
    outputParameters: draft.outputs.map((row) => ({
      name: row.name.trim(),
      type: row.type,
      jsonField: row.jsonField.trim(),
      source: row.source || 'JSON_FIELD',
      headerName: row.headerName?.trim() || '',
    })),
    steps: {
      pagination: draft.pagination,
      nextField: draft.nextField.trim(),
      nextTokenParameter: draft.nextTokenParameter.trim(),
      maxPages: draft.maxPages,
      callStyle: draft.callStyle,
      correlationOutput: draft.correlationOutput.trim(),
      waitTimeoutMillis: draft.waitTimeoutMillis,
    },
  }
}

function snapshot(draft: OperationDraft) {
  return {
    operationKey: draft.operationKey,
    method: draft.method,
    url: draft.url,
    contentType: draft.contentType,
    rawMediaType: draft.rawMediaType,
    staticHeaders: draft.staticHeaders,
    bodyPresence: draft.bodyPresence,
    apiKeyLocation: draft.apiKeyLocation,
    bearerVariable: draft.bearerVariable,
    tokenUrl: draft.tokenUrl,
    clientId: draft.clientId,
    scopes: draft.scopes,
    certificate: draft.certificate,
    signatureHeader: draft.signatureHeader,
    accessKey: draft.accessKey,
    region: draft.region,
    awsService: draft.awsService,
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
      partKind: row.partKind || 'TEXT',
    })),
    outputs: draft.outputs.map((row) => ({
      name: row.name,
      type: row.type,
      jsonField: row.jsonField,
      source: row.source || 'JSON_FIELD',
      headerName: row.headerName || '',
    })),
    pagination: draft.pagination,
    nextField: draft.nextField,
    nextTokenParameter: draft.nextTokenParameter,
    maxPages: draft.maxPages,
    callStyle: draft.callStyle,
    correlationOutput: draft.correlationOutput,
    waitTimeoutMillis: draft.waitTimeoutMillis,
  }
}
