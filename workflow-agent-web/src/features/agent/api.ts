import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  AgentDefinition,
  AgentDefinitionCommand,
  AgentManualRunCommand,
  AgentProvider,
  AgentProviderCommand,
  AgentRun,
  AgentRunDetail,
  AgentRunStatus,
  AgentRunSubmission,
  AgentVersion,
  AgentVersionCommand,
  PublishedAgentVersion,
} from './types'

export interface AgentPageQuery {
  pageNum: number
  pageSize: number
  keyword?: string
  enabled?: boolean
}

export interface AgentRunPageQuery {
  pageNum: number
  pageSize: number
  keyword?: string
  status?: AgentRunStatus
}

export const agentApi = {
  page: (params: AgentPageQuery) =>
    apiClient.get<PageResult<AgentDefinition>>('/agents', { params }),
  create: (payload: AgentDefinitionCommand) => apiClient.post<AgentDefinition>('/agents', payload),
  update: (id: number, payload: AgentDefinitionCommand) =>
    apiClient.post<AgentDefinition>(`/agents/${id}`, payload),
  versions: (definitionId: number) =>
    apiClient.get<AgentVersion[]>(`/agents/${definitionId}/versions`),
  createDraft: (definitionId: number) =>
    apiClient.post<AgentVersion>(`/agents/${definitionId}/versions`),
  updateDraft: (definitionId: number, versionId: number, payload: AgentVersionCommand) =>
    apiClient.post<AgentVersion>(`/agents/${definitionId}/versions/${versionId}`, payload),
  publish: (definitionId: number, versionId: number) =>
    apiClient.post<AgentVersion>(`/agents/${definitionId}/versions/${versionId}/publish`),
  publishedVersions: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    versionId?: number
  }) => apiClient.get<PageResult<PublishedAgentVersion>>('/agents/published-versions', { params }),
}

export const agentProviderApi = {
  page: (params: AgentPageQuery) =>
    apiClient.get<PageResult<AgentProvider>>('/agent-providers', { params }),
  enabled: () => apiClient.get<AgentProvider[]>('/agent-providers/enabled'),
  create: (payload: AgentProviderCommand) =>
    apiClient.post<AgentProvider>('/agent-providers', payload),
  update: (id: number, payload: AgentProviderCommand) =>
    apiClient.post<AgentProvider>(`/agent-providers/${id}`, payload),
}

export const agentRunApi = {
  page: (params: AgentRunPageQuery) =>
    apiClient.get<PageResult<AgentRun>>('/agent-runs', { params }),
  detail: async (runId: number) => {
    const detail = await apiClient.get<AgentRunDetail>(`/agent-runs/${runId}`)
    return {
      ...detail,
      attempts: detail.attempts ?? [],
      steps: detail.steps ?? [],
      modelInvocations: detail.modelInvocations ?? [],
      checkpoints: detail.checkpoints ?? [],
      stateHistory: detail.stateHistory ?? [],
      recoveryDecisions: detail.recoveryDecisions ?? [],
    }
  },
  submitManual: (payload: AgentManualRunCommand) =>
    apiClient.post<AgentRunSubmission>('/agent-runs/manual-tests', payload),
}
