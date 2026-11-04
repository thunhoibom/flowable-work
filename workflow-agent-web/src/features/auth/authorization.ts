import type { AuthUser } from './types'

/**
 * EIM roles (from the token's `eim_roles` claim). Each screen and action lists the roles it
 * accepts, mirroring the backend's `@PreAuthorize("hasAnyRole(...)")`.
 */
export const APP_ROLE = {
  platformAdministrator: 'WF_PLATFORM_ADMIN',
  administrator: 'WF_ADMIN',
  operator: 'WF_OPERATOR',
  viewer: 'WF_VIEWER',
} as const

export type AppRole = (typeof APP_ROLE)[keyof typeof APP_ROLE]

const { platformAdministrator, administrator, operator, viewer } = APP_ROLE

/** Role lists per capability; keep in sync with the backend controllers. */
export const ROLES = {
  anyUser: [platformAdministrator, administrator, operator, viewer],
  designers: [platformAdministrator, administrator],
  operators: [platformAdministrator, administrator, operator],
  platformAdministrators: [platformAdministrator],
} as const satisfies Record<string, readonly AppRole[]>

export interface AccessRequirement {
  requiredAnyRoles?: readonly AppRole[]
}

type AuthorizationSubject = Pick<AuthUser, 'roles'> | null | undefined

export function hasRole(subject: AuthorizationSubject, role: AppRole) {
  return subject?.roles.includes(role) ?? false
}

export function hasAccess(subject: AuthorizationSubject, requirement: AccessRequirement) {
  const roles = requirement.requiredAnyRoles ?? []
  if (roles.length === 0) return true
  return roles.some((role) => hasRole(subject, role))
}

export function canWriteDefinitions(subject: AuthorizationSubject) {
  return hasAccess(subject, { requiredAnyRoles: ROLES.designers })
}

export function canOperateInstances(subject: AuthorizationSubject) {
  return hasAccess(subject, { requiredAnyRoles: ROLES.operators })
}
