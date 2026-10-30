import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  AssignmentRule,
  AssignmentRuleCommand,
  AssignmentRuleInheritResult,
  AssignmentType,
  EmptyUserStrategy,
} from './types'

const ROOT = '/workflow/node-assignment-rule'

export interface AssignmentRuleQuery {
  pageNum: number
  pageSize: number
  processDefinitionKey?: string
  processDefinitionId?: string
  version?: number
  taskDefinitionKey?: string
  variableName?: string
  assignmentType?: AssignmentType
  emptyUserStrategy?: EmptyUserStrategy
}

export const assignmentRuleApi = {
  page: (params: AssignmentRuleQuery) =>
    apiClient.get<PageResult<AssignmentRule>>(ROOT, { params }),
  create: (payload: AssignmentRuleCommand) => apiClient.post<AssignmentRule>(ROOT, payload),
  update: (id: number, payload: AssignmentRuleCommand) =>
    apiClient.post<void>(`${ROOT}/${id}`, payload),
  delete: (id: number) => apiClient.delete<void>(`${ROOT}/${id}`),
  inherit: (processDefinitionId: string) =>
    apiClient.post<AssignmentRuleInheritResult>(`${ROOT}/inherit`, { processDefinitionId }),
}
