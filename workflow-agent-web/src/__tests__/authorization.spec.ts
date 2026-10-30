import { describe, expect, it } from 'vitest'
import {
  APP_PERMISSION,
  APP_ROLE,
  canOperateInstances,
  canWriteDefinitions,
  hasAccess,
} from '@/features/auth/authorization'
import { resolvePostAuthRedirect } from '@/features/auth/redirect'

const ordinaryUser = {
  roles: ['USER'],
  permissions: [APP_PERMISSION.definitionRead, APP_PERMISSION.instanceRead],
}

describe('authorization policy', () => {
  it('keeps administrator elevation and explicit permissions consistent', () => {
    expect(canWriteDefinitions(ordinaryUser)).toBe(false)
    expect(canWriteDefinitions({ roles: [APP_ROLE.tenantAdministrator], permissions: [] })).toBe(
      true,
    )
    expect(
      canOperateInstances({ roles: ['USER'], permissions: [APP_PERMISSION.instanceOperate] }),
    ).toBe(true)
  })

  it('grants a route when any declared role or permission matches', () => {
    expect(
      hasAccess(ordinaryUser, {
        requiredAnyRoles: [APP_ROLE.platformAdministrator],
        requiredAnyPermissions: [APP_PERMISSION.definitionRead],
      }),
    ).toBe(true)
  })

  it('keeps Agent management and runtime audit permissions independently assignable', () => {
    const runAuditor = { roles: ['USER'], permissions: [APP_PERMISSION.agentRunRead] }

    expect(hasAccess(runAuditor, { requiredAnyPermissions: [APP_PERMISSION.agentRunRead] })).toBe(
      true,
    )
    expect(hasAccess(runAuditor, { requiredAnyPermissions: [APP_PERMISSION.agentManage] })).toBe(
      false,
    )
    expect(
      hasAccess(runAuditor, { requiredAnyPermissions: [APP_PERMISSION.agentRunExecute] }),
    ).toBe(false)
  })
})

describe('post-authentication redirects', () => {
  it('allows internal routes and rejects protocol-relative or external redirects', () => {
    expect(resolvePostAuthRedirect('/process-instances?status=running')).toBe(
      '/process-instances?status=running',
    )
    expect(resolvePostAuthRedirect('//evil.example')).toBe('/process-definitions')
    expect(resolvePostAuthRedirect('https://evil.example')).toBe('/process-definitions')
  })
})
