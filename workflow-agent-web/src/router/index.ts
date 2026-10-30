import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { agentsEnabled } from '@/config/product'
import { APP_PERMISSION, APP_ROLE, hasAccess, readAuthSession } from '@/features/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/auth',
      name: 'auth',
      component: () => import('@/features/auth/views/AuthView.vue'),
      meta: { title: 'Đăng nhập', public: true },
    },
    {
      path: '/',
      component: AppShell,
      children: [
        {
          path: '',
          redirect: '/process-definitions',
        },
        {
          path: 'process-definitions',
          name: 'process-definitions',
          component: () =>
            import('@/features/process-definition/views/ProcessDefinitionListView.vue'),
          meta: {
            title: 'Quy trình',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.definitionRead, APP_PERMISSION.definitionWrite],
            navigation: { label: 'Quy trình', icon: 'definitions', order: 10 },
          },
        },
        {
          path: 'process-definitions/designer',
          name: 'process-designer',
          component: () => import('@/features/process-definition/views/ProcessDesignerView.vue'),
          meta: {
            title: 'Thiết kế',
            immersive: true,
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.definitionWrite],
          },
        },
        {
          path: 'process-instances',
          name: 'process-instances',
          component: () => import('@/features/process-instance/views/ProcessInstanceListView.vue'),
          meta: {
            title: 'Công việc',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.instanceRead, APP_PERMISSION.instanceOperate],
            navigation: { label: 'Công việc', icon: 'instances', order: 20 },
          },
        },
        {
          path: 'process-instances/:id',
          name: 'process-instance-detail',
          component: () =>
            import('@/features/process-instance/views/ProcessInstanceDetailView.vue'),
          meta: {
            title: 'Chi tiết công việc',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.instanceRead, APP_PERMISSION.instanceOperate],
          },
        },
        {
          path: 'services',
          name: 'service-catalog',
          component: () => import('@/features/service-registry/ServiceCatalogView.vue'),
          meta: {
            title: 'Dịch vụ',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.definitionWrite],
            navigation: { label: 'Dịch vụ', icon: 'services', order: 35 },
          },
        },
        {
          path: 'assignment-rules',
          name: 'assignment-rules',
          component: () => import('@/features/assignment-rule/views/AssignmentRuleListView.vue'),
          meta: {
            title: 'Phân công',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.assignmentManage],
            navigation: { label: 'Phân công', icon: 'assignments', order: 30 },
          },
        },
        {
          path: 'access',
          name: 'access-management',
          component: () => import('@/features/access/views/AccessManagementView.vue'),
          meta: {
            title: 'Thành viên',
            requiredAnyRoles: [APP_ROLE.platformAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.memberManage, APP_PERMISSION.roleManage],
            navigation: { label: 'Thành viên', icon: 'access', order: 50 },
          },
        },
        {
          path: 'agents',
          name: 'agent-management',
          component: () => import('@/features/agent/views/AgentManagementView.vue'),
          meta: {
            title: 'Agent',
            requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.agentManage, APP_PERMISSION.agentRunRead],
            ...(agentsEnabled
              ? { navigation: { label: 'Agent', icon: 'agents' as const, order: 40 } }
              : {}),
          },
        },
        {
          path: 'tenants',
          name: 'tenants',
          component: () => import('@/features/tenant/views/TenantListView.vue'),
          meta: {
            title: 'Tổ chức',
            requiredAnyRoles: [APP_ROLE.platformAdministrator],
            requiredAnyPermissions: [APP_PERMISSION.tenantManage],
            navigation: { label: 'Tổ chức', icon: 'tenants', order: 60 },
          },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: 'Không tìm thấy trang' },
    },
  ],
})

function firstAuthorizedRoute(session: ReturnType<typeof readAuthSession>) {
  const firstRoute = router
    .getRoutes()
    .filter((route) => route.name && route.meta.navigation && hasAccess(session, route.meta))
    .sort((left, right) => left.meta.navigation!.order - right.meta.navigation!.order)[0]
  if (firstRoute) return { name: firstRoute.name }
  return { name: 'not-found' }
}

router.beforeEach((to) => {
  const session = readAuthSession()
  const { expiresAt } = session ?? {}
  const authenticated = Boolean(expiresAt && Date.parse(expiresAt) > Date.now())

  if (!to.meta.public && !authenticated) {
    return { name: 'auth', query: { redirect: to.fullPath } }
  }
  if (to.name === 'auth' && authenticated) {
    return firstAuthorizedRoute(session)
  }
  if (authenticated && to.name === 'agent-management' && !agentsEnabled) {
    return firstAuthorizedRoute(session)
  }
  if (authenticated && !hasAccess(session, to.meta)) {
    return firstAuthorizedRoute(session)
  }
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? 'Công việc')} | Workflow Agent`
})

export default router
