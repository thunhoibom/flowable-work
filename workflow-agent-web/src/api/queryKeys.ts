type QueryParameters = object

const tenantScope = (tenantCode: string) => ['tenant', tenantCode] as const

export const queryKeys = {
  tenant: tenantScope,
  processDefinitions: (tenantCode: string) =>
    [...tenantScope(tenantCode), 'process-definitions'] as const,
  processDefinitionPage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.processDefinitions(tenantCode), 'page', parameters] as const,
  processDefinitionCatalog: (tenantCode: string) =>
    [...queryKeys.processDefinitions(tenantCode), 'catalog'] as const,
  processDefinitionVersions: (tenantCode: string, processDefinitionKey: string) =>
    [...queryKeys.processDefinitions(tenantCode), 'versions', processDefinitionKey] as const,
  processDefinitionDetail: (tenantCode: string, processDefinitionKey: string, version?: number) =>
    [...queryKeys.processDefinitions(tenantCode), 'detail', processDefinitionKey, version] as const,
  processInstances: (tenantCode: string) =>
    [...tenantScope(tenantCode), 'process-instances'] as const,
  processInstancePage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.processInstances(tenantCode), 'page', parameters] as const,
  processInstanceDetail: (tenantCode: string, id: string) =>
    [...queryKeys.processInstances(tenantCode), 'detail', id] as const,
  processInstanceDiagram: (tenantCode: string, id: string) =>
    [...queryKeys.processInstances(tenantCode), 'diagram', id] as const,
  assignmentRules: (tenantCode: string) =>
    [...tenantScope(tenantCode), 'assignment-rules'] as const,
  assignmentRulePage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.assignmentRules(tenantCode), 'page', parameters] as const,
  tenantMembers: (tenantCode: string) => [...tenantScope(tenantCode), 'members'] as const,
  tenantMemberList: (tenantCode: string, keyword: string) =>
    [...queryKeys.tenantMembers(tenantCode), keyword] as const,
  directoryUsers: (tenantCode: string, parameters: QueryParameters) =>
    [...tenantScope(tenantCode), 'directory-users', 'page', parameters] as const,
  tenantRoles: (tenantCode: string) => [...tenantScope(tenantCode), 'roles'] as const,
  permissions: () => ['platform', 'permissions'] as const,
  tenants: () => ['platform', 'tenants'] as const,
  tenantPage: (parameters: QueryParameters) =>
    [...queryKeys.tenants(), 'page', parameters] as const,
  agents: (tenantCode: string) => [...tenantScope(tenantCode), 'agents'] as const,
  agentPage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.agents(tenantCode), 'page', parameters] as const,
  agentVersions: (tenantCode: string, definitionId: number) =>
    [...queryKeys.agents(tenantCode), 'versions', definitionId] as const,
  agentProviders: (tenantCode: string) => [...tenantScope(tenantCode), 'agent-providers'] as const,
  agentProviderPage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.agentProviders(tenantCode), 'page', parameters] as const,
  enabledAgentProviders: (tenantCode: string) =>
    [...queryKeys.agentProviders(tenantCode), 'enabled'] as const,
  agentRuns: (tenantCode: string) => [...tenantScope(tenantCode), 'agent-runs'] as const,
  agentRunPage: (tenantCode: string, parameters: QueryParameters) =>
    [...queryKeys.agentRuns(tenantCode), 'page', parameters] as const,
  agentRunDetail: (tenantCode: string, runId: number) =>
    [...queryKeys.agentRuns(tenantCode), 'detail', runId] as const,
}
