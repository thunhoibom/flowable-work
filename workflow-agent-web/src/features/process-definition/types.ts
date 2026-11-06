export type PublishStatus = 'all' | 'published' | 'suspended' | 'draft_only'

export interface ProcessDefinitionSummary {
  /** Null for a process that only exists as a draft. */
  processDefinitionId: string | null
  processDefinitionKey: string
  processDefinitionName: string
  latestVersion: number
  latestDeploymentId: string
  latestDeployTime?: string
  activeVersion?: number
  activeProcessDefinitionId?: string
  activeDeploymentId?: string
  activeUpdateTime?: string
  publishStatus: Exclude<PublishStatus, 'all'>
  tenantId: string
  hasUnpublishedChanges: boolean
}

export interface ProcessDefinition {
  processDefinitionId: string
  processDefinitionKey: string
  processDefinitionName: string
  version: number
  deploymentId: string
  deployedAt?: string
  tenantId: string
  active: boolean
  bpmnXml: string
}

export interface ActiveProcessVersion {
  tenantId: string
  processDefinitionKey: string
  processDefinitionId: string
  version: number
  activatedBy?: string
  activatedAt?: string
}

export interface DeployProcessResult {
  deploymentId: string
  processDefinitionId: string
  processDefinitionKey: string
  processDefinitionName: string
  version: number
}

/** The editable copy. `revision === 0` means no draft row yet; the XML is the latest version. */
export interface ProcessDraft {
  processDefinitionKey: string
  name: string
  bpmnXml: string
  revision: number
  updatedBy?: string
  updatedAt?: string
  latestVersion?: number
  suspended: boolean
  hasUnpublishedChanges: boolean
}

export interface AssignmentRuleCopySummary {
  copiedCount: number
  skippedCount: number
  skippedReasons: string[]
}

export interface PublishProcessResult extends DeployProcessResult {
  rules?: AssignmentRuleCopySummary | null
  newUserTaskIds: string[]
}

export interface ProcessRelease {
  version: number
  action: 'PUBLISH' | 'ROLLBACK' | 'SUSPEND' | 'RESUME'
  sourceVersion?: number
  performedBy: string
  performedAt: string
}

/** One call activity link: the caller for "where used", the callee for "calls". */
export interface ProcessReference {
  processDefinitionKey: string
  processDefinitionName?: string
  version?: number
  activityId: string
  published: boolean
}

export interface DefinitionPageQuery {
  pageNum: number
  pageSize: number
  processDefinitionKey?: string
  processDefinitionName?: string
  publishStatus?: PublishStatus
}
