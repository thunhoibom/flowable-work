export interface WorkflowTenant {
  id: number
  tenantId: string
  tenantCode: string
  tenantName: string
  description?: string
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface TenantCommand {
  tenantId: string
  tenantCode: string
  tenantName: string
  description?: string
  enabled: boolean
}
