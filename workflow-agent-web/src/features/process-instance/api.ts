import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  ProcessDiagramData,
  ProcessInstanceDetail,
  ProcessInstanceSummary,
  CompleteTaskRequest,
  CompleteTaskResult,
  ParticipantRequirement,
  ProcessInteraction,
  RejectTaskRequest,
  StartProcessRequest,
  StartProcessResult,
} from './types'

const ROOT = '/workflow/management/process'

export interface InstancePageQuery {
  pageNum: number
  pageSize: number
  processDefinitionKey?: string
  processDefinitionName?: string
  processInstanceId?: string
  businessKey?: string
  status?: 'all' | 'running' | 'finished'
}

export const processInstanceApi = {
  startInteraction: (payload: {
    processDefinitionKey: string
    processDefinitionId?: string
    variables: Record<string, unknown>
  }) => apiClient.post<ProcessInteraction>('/workflow/process/interaction', payload),
  start: (payload: StartProcessRequest) =>
    apiClient.post<StartProcessResult>('/workflow/process/start', payload),
  startParticipantRequirements: (payload: {
    processDefinitionKey: string
    processDefinitionId?: string
    variables: Record<string, unknown>
  }) =>
    apiClient.post<ParticipantRequirement[]>('/workflow/process/participant-requirements', payload),
  page: (params: InstancePageQuery) =>
    apiClient.get<PageResult<ProcessInstanceSummary>>(`${ROOT}/instances/page`, { params }),
  detail: (processInstanceId: string) =>
    apiClient.get<ProcessInstanceDetail>(`${ROOT}/instance/detail`, {
      params: { processInstanceId },
    }),
  diagram: (processInstanceId: string) =>
    apiClient.get<ProcessDiagramData>(`${ROOT}/diagram-data`, {
      params: { processInstanceId },
    }),
  terminate: (processInstanceId: string, reason: string) =>
    apiClient.post<void>(`${ROOT}/instance/terminate`, { processInstanceId, reason }),
  approve: (payload: CompleteTaskRequest) =>
    apiClient.post<CompleteTaskResult>('/workflow/task/approve', payload),
  taskInteraction: (payload: { taskId: string; variables: Record<string, unknown> }) =>
    apiClient.post<ProcessInteraction>('/workflow/task/interaction', payload),
  taskParticipantRequirements: (payload: {
    taskId: string
    action: 'APPROVE' | 'REJECT'
    targetActivityId?: string
    variables: Record<string, unknown>
  }) =>
    apiClient.post<ParticipantRequirement[]>('/workflow/task/participant-requirements', payload),
  reject: (payload: RejectTaskRequest) =>
    apiClient.post<CompleteTaskResult>('/workflow/task/reject', payload),
  transfer: (payload: {
    taskId: string
    currentAssignee?: string
    currentCandidateGroups: string[]
    targetAssignee?: string
    targetCandidateUsers: string[]
    targetCandidateGroups: string[]
    comment?: string
  }) => apiClient.post<void>('/workflow/task/transfer', payload),
}
