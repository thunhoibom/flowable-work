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

export const serviceCatalogApi = {
  list: () => apiClient.get<ServiceView[]>('/services'),
  get: (serviceKey: string) => apiClient.get<ServiceView>(`/services/${serviceKey}`),
  create: (payload: { serviceKey: string; name: string }) =>
    apiClient.post<void>('/services', payload),
  saveOperation: (serviceKey: string, operationKey: string, payload: SaveOperationCommand) =>
    apiClient.put<void>(`/services/${serviceKey}/operations/${operationKey}`, payload),
  publish: (serviceKey: string) => apiClient.post<void>(`/services/${serviceKey}/publish`),
}
