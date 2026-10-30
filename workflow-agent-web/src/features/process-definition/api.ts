import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  ActiveProcessVersion,
  DefinitionPageQuery,
  DeployProcessResult,
  ProcessDefinition,
  ProcessDefinitionSummary,
} from './types'

const ROOT = '/workflow/management/process'

export const definitionApi = {
  page: (params: DefinitionPageQuery) =>
    apiClient.get<PageResult<ProcessDefinitionSummary>>(`${ROOT}/definitions/page`, { params }),
  listProcesses: () => apiClient.get<ProcessDefinition[]>(`${ROOT}/definitions`),
  listVersions: (processDefinitionKey: string) =>
    apiClient.get<ProcessDefinition[]>(`${ROOT}/definitions`, {
      params: { processDefinitionKey },
    }),
  detail: (processDefinitionKey: string, version?: number) =>
    apiClient.get<ProcessDefinition>(`${ROOT}/definition`, {
      params: { processDefinitionKey, version },
    }),
  exists: (processDefinitionKey: string) =>
    apiClient.get<boolean>(`${ROOT}/definition/exists`, { params: { processDefinitionKey } }),
  deploy: (payload: {
    processDefinitionKey: string
    processDefinitionName: string
    bpmnXml: string
  }) => apiClient.post<DeployProcessResult>(`${ROOT}/deploy`, payload),
  activate: (payload: { processDefinitionKey: string; version: number }) =>
    apiClient.post<ActiveProcessVersion>(`${ROOT}/definition/activate`, payload),
  active: (processDefinitionKey: string) =>
    apiClient.get<ActiveProcessVersion>(`${ROOT}/definition/active`, {
      params: { processDefinitionKey },
    }),
  deleteAll: (processDefinitionKey: string) =>
    apiClient.delete<void>(`${ROOT}/definition`, { params: { processDefinitionKey } }),
  deleteVersion: (processDefinitionKey: string, version: number) =>
    apiClient.delete<void>(`${ROOT}/definition/version`, {
      params: { processDefinitionKey, version },
    }),
}
