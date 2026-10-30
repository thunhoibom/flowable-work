import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/features/auth'
import { clearAuthSession, readAuthSession, writeAuthSession } from '@/features/auth'
import type {
  AuthSession,
  AuthUser,
  LoginRequest,
  RegisterRequest,
  TenantOption,
} from '@/features/auth'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(readAuthSession())
  const user = ref<AuthUser | null>(session.value)
  const tenants = ref<TenantOption[]>([])
  const isAuthenticated = computed(() => {
    const expiresAt = session.value?.expiresAt
    return Boolean(expiresAt && Date.parse(expiresAt) > Date.now())
  })

  function saveSession(nextSession: AuthSession) {
    session.value = nextSession
    user.value = nextSession
    writeAuthSession(nextSession)
  }

  async function login(request: LoginRequest) {
    const nextSession = await authApi.login(request)
    saveSession(nextSession)
    return nextSession
  }

  async function register(request: RegisterRequest) {
    const nextSession = await authApi.register(request)
    saveSession(nextSession)
    return nextSession
  }

  async function refreshCurrentUser() {
    if (!session.value) return null
    const currentUser = await authApi.getCurrentUser()
    user.value = currentUser
    session.value = { ...session.value, ...currentUser }
    writeAuthSession(session.value)
    return currentUser
  }

  async function updateProfile(displayName: string) {
    const currentUser = await authApi.updateProfile(displayName)
    user.value = currentUser
    if (session.value) {
      session.value = { ...session.value, ...currentUser }
      writeAuthSession(session.value)
    }
    return currentUser
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    await authApi.changePassword(currentPassword, newPassword)
  }

  async function loadTenants() {
    if (!session.value) return []
    tenants.value = await authApi.getTenants()
    return tenants.value
  }

  async function switchTenant(tenantCode: string) {
    const nextSession = await authApi.switchTenant(tenantCode)
    saveSession(nextSession)
    await loadTenants()
    return nextSession
  }

  async function logout(revokeRemoteSession = true) {
    if (revokeRemoteSession) {
      try {
        await authApi.logout()
      } catch {
        // Local cleanup must still complete when the remote session is already invalid.
      }
    }
    session.value = null
    user.value = null
    tenants.value = []
    clearAuthSession()
  }

  return {
    session,
    user,
    tenants,
    isAuthenticated,
    login,
    register,
    refreshCurrentUser,
    updateProfile,
    changePassword,
    loadTenants,
    switchTenant,
    logout,
  }
})
