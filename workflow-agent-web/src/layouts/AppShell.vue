<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
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
  Route,
  Cable,
  ScrollText,
  UsersRound,
  KeyRound,
  UserRound,
  Workflow,
  X,
} from '@lucide/vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { queryKeys } from '@/api/queryKeys'
import { hasAccess } from '@/features/auth/authorization'
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
const passwordVisible = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileForm = reactive({ displayName: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
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
  if (command === 'password') {
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordVisible.value = true
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

async function submitPassword() {
  if (passwordForm.newPassword.length < 8) {
    ElMessage.warning('Mật khẩu mới cần ít nhất 8 ký tự')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('Hai lần nhập mật khẩu mới không khớp')
    return
  }
  savingPassword.value = true
  try {
    await authStore.changePassword(passwordForm.currentPassword, passwordForm.newPassword)
    passwordVisible.value = false
    ElMessage.success('Đã đổi mật khẩu')
  } catch {
    ElMessage.error('Đổi mật khẩu thất bại. Hãy kiểm tra mật khẩu hiện tại')
  } finally {
    savingPassword.value = false
  }
}

async function handleMobileLogout() {
  appStore.closeMobileNavigation()
  await handleUserCommand('logout')
}

const navigation = computed(() =>
  router
    .getRoutes()
    .filter((item) => item.name && item.meta.navigation && hasAccess(authStore.user, item.meta))
    .sort((left, right) => left.meta.navigation!.order - right.meta.navigation!.order)
    .map((item) => ({
      label: item.meta.navigation!.label,
      to: { name: item.name! },
      icon: navigationIcons[item.meta.navigation!.icon],
    })),
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
                <el-dropdown-item command="profile"
                  ><UserRound :size="15" />Hồ sơ</el-dropdown-item
                >
                <el-dropdown-item command="password"
                  ><KeyRound :size="15" />Đổi mật khẩu</el-dropdown-item
                >
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

    <el-dialog v-model="passwordVisible" title="Đổi mật khẩu" width="min(480px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="submitPassword">
        <el-form-item label="Mật khẩu hiện tại" required>
          <el-input v-model="passwordForm.currentPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="Mật khẩu mới" required>
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            minlength="8"
            maxlength="128"
            placeholder="Ít nhất 8 ký tự"
          />
        </el-form-item>
        <el-form-item label="Xác nhận mật khẩu mới" required>
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            maxlength="128"
            @keyup.enter="submitPassword"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="savingPassword" @click="submitPassword"
          >Xác nhận đổi</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>
