export type AssignmentType =
  'ASSIGNEE' | 'CANDIDATE_USERS' | 'CANDIDATE_GROUPS' | 'COUNTERSIGN_USERS' | 'MIXED'
export type EmptyUserStrategy = 'TO_ASSIGNEE' | 'AUTO_REJECT' | 'AUTO_COMPLETE'
export type RuleOperator =
  'EQ' | 'NE' | 'EXISTS' | 'NOT_EXISTS' | 'IN' | 'NOT_IN' | 'GT' | 'GE' | 'LT' | 'LE'
export type AssignmentTargetType =
  'ASSIGNEE' | 'CANDIDATE_USER' | 'CANDIDATE_GROUP' | 'COUNTERSIGN_USER' | 'FALLBACK_ASSIGNEE'

export interface AssignmentCondition {
  id?: number
  variableName: string
  operator: RuleOperator
  variableValue?: string
  sortOrder: number
}

export interface AssignmentTarget {
  id?: number
  targetType: AssignmentTargetType
  targetValue: string
  sortOrder: number
}

export interface AssignmentRule {
  id: number
  tenantId: string
  processDefinitionId: string
  processDefinitionKey: string
  version: number
  taskDefinitionKey: string
  priority: number
  assignmentType: AssignmentType
  emptyUserStrategy: EmptyUserStrategy
  enabled: boolean
  description?: string
  conditions: AssignmentCondition[]
  targets: AssignmentTarget[]
  createdAt?: string
  updatedAt?: string
}

export interface AssignmentRuleCommand {
  processDefinitionId: string
  taskDefinitionKey: string
  priority: number
  assignmentType: AssignmentType
  assignees: string[]
  candidateUsers: string[]
  candidateGroups: string[]
  countersignUsers: string[]
  emptyUserStrategy: EmptyUserStrategy
  fallbackAssignee?: string
  enabled: boolean
  description?: string
  conditions: Array<Omit<AssignmentCondition, 'id'>>
}

export interface AssignmentRuleInheritResult {
  sourceProcessDefinitionId: string
  sourceVersion: number
  targetProcessDefinitionId: string
  targetVersion: number
  copiedCount: number
  skippedCount: number
  skippedReasons: string[]
}
