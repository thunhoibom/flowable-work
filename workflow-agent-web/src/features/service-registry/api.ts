import { apiClient } from '@/api/http'

export interface CatalogInputParameter {
  name: string
  type: string
  required: boolean
  location: string
  partKind?: string
}

export interface CatalogOutputParameter {
  name: string
  type: string
  jsonField: string
  source?: string
  headerName?: string
}

export interface CatalogErrorRule {
  patterns: string[]
  bpmnErrorCode: string
}

export interface CatalogStaticHeader {
  name: string
  value: string
}

export interface CatalogStep {
  pagination?: string
  nextField?: string
  nextTokenParameter?: string
  maxPages?: number
  callStyle?: string
  correlationOutput?: string
  waitTimeoutMillis?: number
}

export interface CatalogOperation {
  operationKey: string
  method: string
  urlTemplate: string
  authType: string
  apiKeyHeader?: string
  apiKeyLocation?: string
  secretPresent: boolean
  bodyTemplate?: string
  contentType?: string
  rawMediaType?: string
  staticHeaders?: CatalogStaticHeader[]
  bodyPresence?: string
  bearerVariable?: string
  tokenUrl?: string
  clientId?: string
  scopes?: string[]
  certificatePresent?: boolean
  signatureHeader?: string
  accessKeyPresent?: boolean
  region?: string
  awsService?: string
  timeoutMillis: number
  /** Absent on operations saved before auth modes existed. The editor treats that as OVERRIDE. */
  authMode?: string
  connectTimeoutMillis?: number
  errorRules?: CatalogErrorRule[]
  successStatuses?: string[]
  errorStatusCodes: string[]
  bpmnErrorCode: string
  publishedUrl?: string
  publishedMethod?: string
  publishedBpmnErrorCode?: string
  publishedContentType?: string
  inputParameters?: CatalogInputParameter[]
  outputParameters?: CatalogOutputParameter[]
  publishedInputParameters?: CatalogInputParameter[]
  publishedOutputParameters?: CatalogOutputParameter[]
  steps?: CatalogStep
  publishedSteps?: CatalogStep
}

export interface ServiceBaseUrl {
  environment: string
  url: string
}

export interface ServiceSharedAuth {
  type: string
  apiKeyHeader?: string | null
  secretPresent: boolean
  apiKeyLocation?: string
  bearerVariable?: string
  tokenUrl?: string
  clientId?: string
  scopes?: string[]
  certificatePresent?: boolean
  signatureHeader?: string
  accessKeyPresent?: boolean
  region?: string
  awsService?: string
}

export interface ServiceView {
  serviceKey: string
  name: string
  description?: string
  baseUrls?: ServiceBaseUrl[]
  sharedAuth?: ServiceSharedAuth
  operations: CatalogOperation[]
}

export interface SaveServiceSettingsCommand {
  name: string
  description: string
  baseUrls: ServiceBaseUrl[]
  sharedAuth: {
    type: string
    apiKeyHeader: string
    secret: string
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
  }
}

export interface SaveOperationCommand {
  method: string
  url: string
  authType: string
  apiKeyHeader?: string
  secret?: string
  bodyTemplate?: string
  timeoutMillis: number
  contentType: string
  rawMediaType?: string
  staticHeaders: CatalogStaticHeader[]
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
  authMode: string
  connectTimeoutMillis: number
  errorRules: CatalogErrorRule[]
  /** Comma-separated extra success statuses. The server splits this. */
  successStatuses: string
  errorStatusCodes: string[]
  bpmnErrorCode: string
  inputParameters?: CatalogInputParameter[]
  outputParameters?: CatalogOutputParameter[]
  steps?: CatalogStep
}

function servicePath(serviceKey: string) {
  if (!serviceKey) throw new Error('Thiếu mã dịch vụ')
  return `/services/${serviceKey}`
}

export const serviceCatalogApi = {
  list: () => apiClient.get<ServiceView[]>('/services'),
  get: (serviceKey: string) => apiClient.get<ServiceView>(servicePath(serviceKey)),
  create: (payload: { serviceKey: string; name: string }) =>
    apiClient.post<void>('/services', payload),
  update: (serviceKey: string, payload: { name: string }) =>
    apiClient.post<void>('/services', { serviceKey, name: payload.name }),
  saveSettings: (serviceKey: string, payload: SaveServiceSettingsCommand) =>
    apiClient.put<void>(`${servicePath(serviceKey)}/settings`, payload),
  saveOperation: (serviceKey: string, operationKey: string, payload: SaveOperationCommand) =>
    apiClient.put<void>(`${servicePath(serviceKey)}/operations/${operationKey}`, payload),
  publish: (serviceKey: string) => apiClient.post<void>(`${servicePath(serviceKey)}/publish`),
  deleteOperation: (serviceKey: string, operationKey: string) =>
    apiClient.delete<void>(`${servicePath(serviceKey)}/operations/${operationKey}`),
  deleteService: (serviceKey: string) => apiClient.delete<void>(servicePath(serviceKey)),
}
