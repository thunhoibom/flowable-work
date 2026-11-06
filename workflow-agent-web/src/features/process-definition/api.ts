import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  DefinitionPageQuery,
  DeployProcessResult,
  ProcessDefinition,
  ProcessDefinitionSummary,
  ProcessDraft,
  ProcessRelease,
  PublishProcessResult,
} from './types'

const ROOT = '/workflow/management/process'
const key = (processDefinitionKey: string) => encodeURIComponent(processDefinitionKey)

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
  /** CI path: overwrite the draft and publish at once. The designer uses saveDraft + publish. */
  deploy: (payload: {
    processDefinitionKey: string
    processDefinitionName: string
    bpmnXml: string
  }) => apiClient.post<DeployProcessResult>(`${ROOT}/deploy`, payload),

  draft: (processDefinitionKey: string) =>
    apiClient.get<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}`),
  createDraft: (payload: { processDefinitionKey: string; name: string; bpmnXml: string }) =>
    apiClient.post<ProcessDraft>(`${ROOT}/drafts`, payload),
  saveDraft: (
    processDefinitionKey: string,
    payload: { name: string; bpmnXml: string; revision: number },
  ) => apiClient.put<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}`, payload),
  loadVersionIntoDraft: (
    processDefinitionKey: string,
    payload: { version: number; revision: number },
  ) =>
    apiClient.post<ProcessDraft>(
      `${ROOT}/drafts/${key(processDefinitionKey)}/load-version`,
      payload,
    ),
  discardDraft: (processDefinitionKey: string, revision: number) =>
    apiClient.post<ProcessDraft>(`${ROOT}/drafts/${key(processDefinitionKey)}/discard`, {
      revision,
    }),

  publish: (processDefinitionKey: string, revision: number) =>
    apiClient.post<PublishProcessResult>(`${ROOT}/${key(processDefinitionKey)}/publish`, {
      revision,
    }),
  rollback: (processDefinitionKey: string, version: number) =>
    apiClient.post<PublishProcessResult>(`${ROOT}/${key(processDefinitionKey)}/rollback`, {
      version,
    }),
  suspend: (processDefinitionKey: string) =>
    apiClient.post<ProcessRelease>(`${ROOT}/${key(processDefinitionKey)}/suspend`),
  resume: (processDefinitionKey: string) =>
    apiClient.post<ProcessRelease>(`${ROOT}/${key(processDefinitionKey)}/resume`),
  releases: (processDefinitionKey: string) =>
    apiClient.get<ProcessRelease[]>(`${ROOT}/${key(processDefinitionKey)}/releases`),

  deleteAll: (processDefinitionKey: string) =>
    apiClient.delete<void>(`${ROOT}/definition`, { params: { processDefinitionKey } }),
  deleteVersion: (processDefinitionKey: string, version: number) =>
    apiClient.delete<void>(`${ROOT}/definition/version`, {
      params: { processDefinitionKey, version },
    }),
}
