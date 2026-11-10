<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Building2,
  Bot,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  GitBranch,
  LogOut,
  Menu,
  RefreshCw,
  Route,
  Cable,
  RadioTower,
  ScrollText,
  UsersRound,
  UserRound,
  Workflow,
  X,
} from '@lucide/vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { queryKeys } from '@/api/queryKeys'
import { getSitemap } from '@/features/auth/api'
import { buildNavigation } from '@/features/auth/menu'
import type { NavigationIcon } from '@/router/meta'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const authStore = useAuthStore()

const pageTitle = computed(() => String(route.meta.title ?? 'Công việc'))
const displayName = computed(
  () => authStore.user?.displayName || authStore.user?.username || 'Người dùng',
)
const userInitial = computed(() => displayName.value.slice(0, 1).toUpperCase())
const selectedTenant = ref(authStore.user?.tenantCode || '')
const switchingTenant = ref(false)
const profileVisible = ref(false)
const savingProfile = ref(false)
const profileForm = reactive({ displayName: '' })
const sidebarCollapsed = ref(false)
const sidebarToggleButton = ref<HTMLButtonElement>()
const SIDEBAR_COLLAPSED_KEY = 'workflow-agent.sidebar-collapsed'
let authorizationRefreshTimer: ReturnType<typeof setInterval> | undefined
let refreshingAuthorization = false
const navigationIcons: Record<NavigationIcon, Component> = {
  definitions: ScrollText,
  instances: GitBranch,
  assignments: Route,
  agents: Bot,
  services: Cable,
  events: RadioTower,
  access: UsersRound,
  tenants: Building2,
}

watch(
  () => authStore.user?.tenantCode,
  (tenantCode) => (selectedTenant.value = tenantCode || ''),
)

async function refreshAuthorization(showError = false) {
  if (!authStore.isAuthenticated || refreshingAuthorization) return
  refreshingAuthorization = true
  try {
    await Promise.all([authStore.refreshCurrentUser(), authStore.loadTenants()])
  } catch {
    if (showError) ElMessage.error('Không tải lại được quyền của bạn')
  } finally {
    refreshingAuthorization = false
  }
}

function handleWindowFocus() {
  void refreshAuthorization()
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') void refreshAuthorization()
}

onMounted(async () => {
  sidebarCollapsed.value = window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true'
  sidebarToggleButton.value?.setAttribute('aria-expanded', String(!sidebarCollapsed.value))
  await refreshAuthorization(true)
  window.addEventListener('focus', handleWindowFocus)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  authorizationRefreshTimer = window.setInterval(() => void refreshAuthorization(), 30_000)
})

watch(sidebarCollapsed, (collapsed) => {
  window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed))
  sidebarToggleButton.value?.setAttribute('aria-expanded', String(!collapsed))
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', handleWindowFocus)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  if (authorizationRefreshTimer) window.clearInterval(authorizationRefreshTimer)
})

async function handleTenantChange(tenantCode: string) {
  if (!tenantCode || tenantCode === authStore.user?.tenantCode) return
  const previousTenantCode = authStore.user?.tenantCode
  switchingTenant.value = true
  try {
    await authStore.switchTenant(tenantCode)
    if (previousTenantCode) {
      queryClient.removeQueries({ queryKey: queryKeys.tenant(previousTenantCode) })
    }
    await queryClient.invalidateQueries({ queryKey: queryKeys.tenant(tenantCode) })
    ElMessage.success(
      `Đã chuyển sang ${authStore.tenants.find((item) => item.tenantCode === tenantCode)?.tenantName || tenantCode}`,
    )
  } catch {
    selectedTenant.value = authStore.user?.tenantCode || ''
    ElMessage.error('Không chuyển được tổ chức')
  } finally {
    switchingTenant.value = false
  }
}

async function handleMobileTenantChange(tenantCode: string) {
  await handleTenantChange(tenantCode)
  appStore.closeMobileNavigation()
}

async function handleUserCommand(command: string) {
  if (command === 'profile') {
    profileForm.displayName = authStore.user?.displayName || ''
    profileVisible.value = true
    return
  }
  if (command === 'logout') {
    await authStore.logout()
    await router.replace({ name: 'auth' })
  }
}

async function submitProfile() {
  const displayName = profileForm.displayName.trim()
  if (!displayName) {
    ElMessage.warning('Hãy nhập biệt danh')
    return
  }
  savingProfile.value = true
  try {
    await authStore.updateProfile(displayName)
    profileVisible.value = false
    ElMessage.success('Đã cập nhật hồ sơ')
  } catch {
    ElMessage.error('Cập nhật hồ sơ thất bại')
  } finally {
    savingProfile.value = false
  }
}

async function handleMobileLogout() {
  appStore.closeMobileNavigation()
  await handleUserCommand('logout')
}

// Menu entries come from EIM (GET /auth/sitemaps), like the rsa-core-service portal.
const sitemapQuery = useQuery({
  queryKey: computed(() => queryKeys.sitemap(authStore.user?.userId || '')),
  queryFn: getSitemap,
  enabled: computed(() => Boolean(authStore.user?.userId)),
  staleTime: 5 * 60_000,
  retry: false,
})

const navigation = computed(() =>
  buildNavigation(router.getRoutes(), sitemapQuery.data.value ?? [], authStore.user).map(
    (item) => ({
      label: item.label,
      to: { name: item.routeName },
      icon: navigationIcons[item.icon],
    }),
  ),
)
</script>

<template>
  <div class="app-shell" :class="{ 'is-sidebar-collapsed': sidebarCollapsed }">
    <aside class="sidebar" aria-label="Menu chính">
      <div class="brand">
        <span class="brand-mark"><Workflow :size="21" /></span>
        <span>
          <strong>Workflow Agent</strong>
          <small>Bàn soạn quy trình</small>
        </span>
      </div>
      <button
        ref="sidebarToggleButton"
        class="sidebar-toggle"
        type="button"
        :title="sidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'"
        :aria-label="sidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'"
        :aria-expanded="sidebarCollapsed ? ('false' as const) : ('true' as const)"
        @click="sidebarCollapsed = !sidebarCollapsed"
      >
        <ChevronRight v-if="sidebarCollapsed" :size="16" />
        <ChevronLeft v-else :size="16" />
      </button>

      <nav class="navigation">
        <div v-if="sitemapQuery.isError.value" class="navigation-error" role="alert">
          <span>Không tải được menu từ EIM</span>
          <el-button
            text
            size="small"
            :loading="sitemapQuery.isFetching.value"
            @click="sitemapQuery.refetch()"
          >
            <RefreshCw :size="14" />Thử lại
          </el-button>
        </div>
        <RouterLink
          v-for="item in navigation"
          :key="String(item.to.name)"
          :to="item.to"
          class="nav-item"
          :title="sidebarCollapsed ? item.label : undefined"
          :aria-label="item.label"
        >
          <component :is="item.icon" :size="18" />
          <span class="nav-item-label">{{ item.label }}</span>
        </RouterLink>
      </nav>
    </aside>

    <el-drawer
      v-model="appStore.mobileNavigationOpen"
      direction="ltr"
      size="292px"
      :show-close="false"
      class="mobile-drawer"
    >
      <template #header>
        <div class="drawer-header">
          <div class="brand brand-mobile">
            <span class="brand-mark"><Workflow :size="20" /></span>
            <span>
              <strong>Workflow Agent</strong>
              <small>Bàn soạn quy trình</small>
            </span>
          </div>
          <el-tooltip content="Đóng menu" placement="bottom">
            <button
              class="icon-button"
              type="button"
              aria-label="Đóng menu"
              @click="appStore.closeMobileNavigation"
            >
              <X :size="19" />
            </button>
          </el-tooltip>
        </div>
      </template>
      <nav class="navigation navigation-mobile">
        <div v-if="sitemapQuery.isError.value" class="navigation-error" role="alert">
          <span>Không tải được menu từ EIM</span>
          <el-button
            text
            size="small"
            :loading="sitemapQuery.isFetching.value"
            @click="sitemapQuery.refetch()"
          >
            <RefreshCw :size="14" />Thử lại
          </el-button>
        </div>
        <RouterLink
          v-for="item in navigation"
          :key="String(item.to.name)"
          :to="item.to"
          class="nav-item"
          @click="appStore.closeMobileNavigation"
        >
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="mobile-session">
        <div class="mobile-session__tenant">
          <span>Tổ chức</span>
          <el-select
            v-model="selectedTenant"
            class="mobile-tenant-switcher"
            popper-class="tenant-switcher-dropdown"
            :loading="switchingTenant"
            aria-label="Chuyển tổ chức"
            @change="handleMobileTenantChange"
          >
            <template #prefix><Building2 :size="16" /></template>
            <el-option
              v-for="tenant in authStore.tenants"
              :key="tenant.tenantCode"
              :label="tenant.tenantName"
              :value="tenant.tenantCode"
              :disabled="!tenant.enabled"
            />
          </el-select>
        </div>
        <div class="mobile-session__account">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-menu-copy">
            <strong>{{ displayName }}</strong>
            <small>{{ authStore.user?.username }}</small>
          </span>
          <el-button text type="danger" aria-label="Đăng xuất" @click="handleMobileLogout">
            <LogOut :size="16" />Đăng xuất
          </el-button>
        </div>
      </div>
    </el-drawer>

    <div class="workspace">
      <header class="topbar">
        <el-tooltip content="Mở menu" placement="bottom">
          <button
            class="icon-button mobile-menu-button"
            type="button"
            aria-label="Mở menu"
            @click="appStore.openMobileNavigation"
          >
            <Menu :size="20" />
          </button>
        </el-tooltip>
        <div class="topbar-title">
          <span>Đang xem</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="topbar-badges">
          <el-select
            v-model="selectedTenant"
            class="tenant-switcher"
            popper-class="tenant-switcher-dropdown"
            :loading="switchingTenant"
            aria-label="Chuyển tổ chức"
            @change="handleTenantChange"
          >
            <template #prefix><Building2 :size="16" /></template>
            <el-option
              v-for="tenant in authStore.tenants"
              :key="tenant.tenantCode"
              :label="tenant.tenantName"
              :value="tenant.tenantCode"
              :disabled="!tenant.enabled"
            />
          </el-select>
          <el-dropdown
            trigger="click"
            popper-class="user-account-dropdown"
            @command="handleUserCommand"
          >
            <button class="user-menu" type="button" aria-label="Mở menu tài khoản">
              <span class="user-avatar">{{ userInitial }}</span>
              <span class="user-menu-copy">
                <strong>{{ displayName }}</strong>
                <small>{{ authStore.user?.username }}</small>
              </span>
              <ChevronDown :size="15" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item class="user-account-summary" disabled>
                  <span class="user-dropdown-avatar">{{ userInitial }}</span>
                  <span class="user-dropdown-copy">
                    <strong>{{ displayName }}</strong>
                    <small>{{ authStore.user?.username }}</small>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="profile"><UserRound :size="15" />Hồ sơ</el-dropdown-item>
                <el-dropdown-item divided command="logout"
                  ><LogOut :size="15" />Đăng xuất</el-dropdown-item
                >
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="content" :class="{ 'content-immersive': route.meta.immersive }">
        <RouterView />
      </main>
    </div>

    <el-dialog v-model="profileVisible" title="Hồ sơ" width="min(480px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="submitProfile">
        <el-form-item label="Tên đăng nhập">
          <el-input :model-value="authStore.user?.username || ''" disabled />
        </el-form-item>
        <el-form-item label="Biệt danh" required>
          <el-input
            v-model="profileForm.displayName"
            maxlength="128"
            show-word-limit
            placeholder="Nhập biệt danh"
            @keyup.enter="submitProfile"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="savingProfile" @click="submitProfile">Lưu</el-button>
      </template>
    </el-dialog>
  </div>
</template>
