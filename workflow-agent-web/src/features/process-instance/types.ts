export interface ProcessInstanceSummary {
  processInstanceId: string
  processDefinitionId: string
  processDefinitionKey: string
  processDefinitionName: string
  processDefinitionVersion?: number
  latestTaskId?: string
  businessKey?: string
  startUserId?: string
  startTime?: string
  endTime?: string
  lastUpdateTime?: string
  durationInMillis?: number
  deleteReason?: string
  status: string
  tenantId: string
  /** Set when a call activity of that instance started this one. */
  superProcessInstanceId?: string
}

export interface ProcessInstanceLink {
  processInstanceId: string
  processDefinitionKey?: string
  processDefinitionName?: string
  businessKey?: string
  status: string
}

export interface ChildProcessInstance {
  callActivityId?: string
  callActivityName?: string
  instance: ProcessInstanceSummary
}

export interface TaskItem {
  taskId: string
  taskDefinitionKey: string
  taskName: string
  assignee?: string
  candidateUsers: string[]
  candidateGroups: string[]
  status: string
  deleteReason?: string
  startTime?: string
  endTime?: string
  durationInMillis?: number
}

export interface VariableItem {
  variableId: string
  variableName: string
  variableTypeName: string
  value: unknown
  executionId?: string
  taskId?: string
  createTime?: string
  lastUpdatedTime?: string
  scopeId?: string
  subScopeId?: string
  scopeType?: string
}

export interface ProcessInstanceDetail {
  instance: ProcessInstanceSummary
  tasks: TaskItem[]
  variables: VariableItem[]
  /** Root first; empty for a root instance. */
  ancestors: ProcessInstanceLink[]
  /** The parent's call activity that started this instance. */
  callActivityId?: string
  children: ChildProcessInstance[]
}

export interface ActivityDetail {
  activityId: string
  activityName?: string
  activityType: string
  assignee?: string
  candidateUsers: string[]
  candidateGroups: string[]
  startTime?: string
  endTime?: string
  durationInMillis?: number
  comment?: string
}

export interface ProcessDiagramData {
  bpmnXml: string
  completedActivityIds: string[]
  activeActivityIds: string[]
  highlightedFlows: string[]
  activityDetails: Record<string, ActivityDetail>
}

export interface StartProcessRequest {
  processDefinitionKey: string
  processDefinitionId?: string
  businessKey?: string
  variables: Record<string, unknown>
  participantAssignments: ParticipantAssignment[]
}

export type InteractionDataType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'

export interface InteractionDataField {
  variablePath: string
  label: string
  description?: string
  dataType: InteractionDataType
  format?: string
  required: boolean
  currentValue?: unknown
  agentActivityId: string
  agentActivityName: string
  agentInputPath: string
}

export interface ProcessInteraction {
  fields: InteractionDataField[]
  agentActivityIds: string[]
}

export type ParticipantAssignmentType =
  'ASSIGNEE' | 'CANDIDATE_USERS' | 'CANDIDATE_GROUPS' | 'COUNTERSIGN_USERS' | 'MIXED'

export interface ParticipantRequirement {
  activityId: string
  activityName: string
  assignmentType: ParticipantAssignmentType
  multiple: boolean
  required: boolean
}

export interface ParticipantAssignment {
  activityId: string
  usernames: string[]
}

export interface RuntimeTask extends TaskItem {
  processInstanceId: string
  processDefinitionId: string
}

export interface StartProcessResult {
  processInstanceId: string
  processDefinitionId: string
  processDefinitionKey: string
  businessKey?: string
  activeTasks: RuntimeTask[]
}

export interface CompleteTaskRequest {
  taskId: string
  currentAssignee?: string
  currentCandidateGroups: string[]
  comment?: string
  variables: Record<string, unknown>
  participantAssignments: ParticipantAssignment[]
}

export interface RejectTaskRequest extends CompleteTaskRequest {
  targetActivityId?: string
  targetAssignees: string[]
  targetCandidateGroups: string[]
}

export interface CompleteTaskResult {
  completedTaskId: string
  processInstanceId: string
  processEnded: boolean
  nextTasks: RuntimeTask[]
}

/** A step the instance is waiting at. Only manual wait steps for now. */
export interface WaitCorrelation {
  name: string
  expression?: string | null
  currentValue?: string | null
  /** False when the expression is not a plain variable path: it is never re-run. */
  evaluated: boolean
}

export interface ProcessWait {
  kind: 'MANUAL' | 'EVENT'
  activityId: string
  activityName?: string
  executionId: string
  since?: string
  elementType?: 'receiveTask' | 'intermediateCatchEvent' | 'boundaryEvent'
  eventKey?: string | null
  correlations?: WaitCorrelation[]
  /** Null when it cannot be checked. */
  correlationChanged?: boolean | null
  dueDate?: string | null
}

export interface TriggerReceiveTaskRequest {
  processInstanceId: string
  activityId: string
  executionId?: string
  variables?: Record<string, unknown>
  reason: string
}
