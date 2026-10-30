import { apiClient } from '@/api/http'

export interface CatalogInputParameter {
  name: string
  type: string
  required: boolean
  location: string
}

export interface CatalogOutputParameter {
  name: string
  type: string
  jsonField: string
}

export interface CatalogOperation {
  operationKey: string
  method: string
  urlTemplate: string
  authType: string
  apiKeyHeader?: string
  secretPresent: boolean
  bodyTemplate?: string
  timeoutMillis: number
  errorStatusCodes: string[]
  bpmnErrorCode: string
  publishedUrl?: string
  publishedMethod?: string
  publishedBpmnErrorCode?: string
  inputParameters?: CatalogInputParameter[]
  outputParameters?: CatalogOutputParameter[]
  publishedInputParameters?: CatalogInputParameter[]
  publishedOutputParameters?: CatalogOutputParameter[]
}

export interface ServiceView {
  serviceKey: string
  name: string
  operations: CatalogOperation[]
}

export interface SaveOperationCommand {
  method: string
  url: string
  authType: string
  apiKeyHeader?: string
  secret?: string
  bodyTemplate?: string
  timeoutMillis: number
  errorStatusCodes: string[]
  bpmnErrorCode: string
  inputParameters?: CatalogInputParameter[]
  outputParameters?: CatalogOutputParameter[]
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
  saveOperation: (serviceKey: string, operationKey: string, payload: SaveOperationCommand) =>
    apiClient.put<void>(`${servicePath(serviceKey)}/operations/${operationKey}`, payload),
  publish: (serviceKey: string) => apiClient.post<void>(`${servicePath(serviceKey)}/publish`),
  deleteOperation: (serviceKey: string, operationKey: string) =>
    apiClient.delete<void>(`${servicePath(serviceKey)}/operations/${operationKey}`),
  deleteService: (serviceKey: string) => apiClient.delete<void>(servicePath(serviceKey)),
}
