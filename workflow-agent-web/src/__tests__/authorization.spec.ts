import { describe, expect, it } from 'vitest'
import type { RouteRecordNormalized } from 'vue-router'
import {
  APP_ROLE,
  canOperateInstances,
  canWriteDefinitions,
  hasAccess,
  ROLES,
} from '@/features/auth/authorization'
import { buildNavigation } from '@/features/auth/menu'
import { resolvePostAuthRedirect } from '@/features/auth/redirect'
import type { SitemapEntry } from '@/features/auth/types'

const user = (...roles: string[]) => ({ roles })

describe('role-based authorization (eim_roles)', () => {
  it('mirrors the backend role lists', () => {
    expect(canWriteDefinitions(user(APP_ROLE.administrator))).toBe(true)
    expect(canWriteDefinitions(user(APP_ROLE.operator))).toBe(false)
    expect(canOperateInstances(user(APP_ROLE.operator))).toBe(true)
    expect(canOperateInstances(user(APP_ROLE.viewer))).toBe(false)
    expect(hasAccess(user(APP_ROLE.viewer), { requiredAnyRoles: ROLES.anyUser })).toBe(true)
  })

  it('ignores roles of other applications', () => {
    expect(hasAccess(user('RSA_ADMIN'), { requiredAnyRoles: ROLES.anyUser })).toBe(false)
    expect(hasAccess(null, { requiredAnyRoles: ROLES.anyUser })).toBe(false)
  })
})

describe('EIM menu', () => {
  const route = (
    name: string,
    menuCode: string,
    label: string,
    roles: readonly string[] = ROLES.anyUser,
  ) =>
    ({
      name,
      meta: {
        requiredAnyRoles: roles,
        navigation: { menuCode, label, icon: 'definitions', order: 0 },
      },
    }) as unknown as RouteRecordNormalized
  const routes = [
    route('process-definitions', 'WF_DEFINITION', 'Quy trình'),
    route('process-instances', 'WF_INSTANCE', 'Công việc'),
    route('tenants', 'WF_TENANT', 'Tổ chức', ROLES.platformAdministrators),
  ]
  const entry = (
    code: string,
    name: string | null,
    children: SitemapEntry[] = [],
  ): SitemapEntry => ({ code, name, url: null, order: 0, privileges: [], children })

  it('shows the screens listed in the sitemap, in EIM order and with EIM names', () => {
    const sitemap = [
      entry('WF_ROOT', 'Quy trình nghiệp vụ', [
        entry('WF_INSTANCE', 'Việc của tôi'),
        entry('WF_DEFINITION', null),
      ]),
    ]

    expect(buildNavigation(routes, sitemap, user(APP_ROLE.viewer))).toEqual([
      { routeName: 'process-instances', label: 'Việc của tôi', icon: 'definitions' },
      { routeName: 'process-definitions', label: 'Quy trình', icon: 'definitions' },
    ])
  })

  it('hides a listed screen whose roles the user lacks', () => {
    const sitemap = [entry('WF_TENANT', 'Tổ chức')]

    expect(buildNavigation(routes, sitemap, user(APP_ROLE.administrator))).toEqual([])
    expect(buildNavigation(routes, sitemap, user(APP_ROLE.platformAdministrator))).toHaveLength(1)
  })

  it('shows nothing without a sitemap', () => {
    expect(buildNavigation(routes, [], user(APP_ROLE.platformAdministrator))).toEqual([])
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
