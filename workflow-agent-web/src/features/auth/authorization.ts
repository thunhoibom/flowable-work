import type { AuthUser } from './types'

export const APP_ROLE = {
  platformAdministrator: 'PLATFORM_ADMIN',
  tenantAdministrator: 'TENANT_ADMIN',
} as const

export const APP_PERMISSION = {
  definitionRead: 'workflow:definition:read',
  definitionWrite: 'workflow:definition:write',
  instanceRead: 'workflow:instance:read',
  instanceOperate: 'workflow:instance:operate',
  assignmentManage: 'assignment:manage',
  memberManage: 'member:manage',
  roleManage: 'role:manage',
  tenantManage: 'tenant:manage',
  agentManage: 'agent:manage',
  agentRunRead: 'agent:run:read',
  agentRunExecute: 'agent:run:execute',
} as const

export type AppRole = (typeof APP_ROLE)[keyof typeof APP_ROLE]
export type AppPermission = (typeof APP_PERMISSION)[keyof typeof APP_PERMISSION]

export interface AccessRequirement {
  requiredAnyRoles?: readonly AppRole[]
  requiredAnyPermissions?: readonly AppPermission[]
}

type AuthorizationSubject = Pick<AuthUser, 'roles' | 'permissions'> | null | undefined

export function hasRole(subject: AuthorizationSubject, role: AppRole) {
  return subject?.roles.includes(role) ?? false
}

export function hasPermission(subject: AuthorizationSubject, permission: AppPermission) {
  return subject?.permissions.includes(permission) ?? false
}

export function hasAccess(subject: AuthorizationSubject, requirement: AccessRequirement) {
  const roles = requirement.requiredAnyRoles ?? []
  const permissions = requirement.requiredAnyPermissions ?? []
  if (roles.length === 0 && permissions.length === 0) return true
  return (
    roles.some((role) => hasRole(subject, role)) ||
    permissions.some((permission) => hasPermission(subject, permission))
  )
}

export function canWriteDefinitions(subject: AuthorizationSubject) {
  return hasAccess(subject, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.definitionWrite],
  })
}

export function canOperateInstances(subject: AuthorizationSubject) {
  return hasAccess(subject, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.instanceOperate],
  })
}
