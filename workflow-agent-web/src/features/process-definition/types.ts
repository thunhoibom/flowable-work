export type PublishStatus = 'all' | 'published' | 'unpublished'

export interface ProcessDefinitionSummary {
  processDefinitionId: string
  processDefinitionKey: string
  processDefinitionName: string
  latestVersion: number
  latestDeploymentId: string
  latestDeployTime?: string
  activeVersion?: number
  activeProcessDefinitionId?: string
  activeDeploymentId?: string
  activeUpdateTime?: string
  publishStatus: string
  tenantId: string
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

export interface DefinitionPageQuery {
  pageNum: number
  pageSize: number
  processDefinitionKey?: string
  processDefinitionName?: string
  publishStatus?: PublishStatus
}
