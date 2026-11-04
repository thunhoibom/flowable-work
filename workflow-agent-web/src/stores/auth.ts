import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { SESSION_REFRESHED_EVENT } from '@/api/http'
import * as authApi from '@/features/auth'
import { clearAuthSession, readAuthSession, writeAuthSession } from '@/features/auth'
import type {
  AuthSession,
  AuthUser,
  LoginRequest,
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

  // The HTTP client renews the SSO access token transparently; keep the store's expiry in sync.
  window.addEventListener(SESSION_REFRESHED_EVENT, (event) => {
    const refreshed = (event as CustomEvent<AuthSession>).detail
    if (!session.value || !refreshed) return
    session.value = { ...session.value, ...refreshed }
    user.value = session.value
  })

  async function login(request: LoginRequest) {
    const nextSession = await authApi.login(request)
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

  async function loadTenants() {
    if (!session.value) return []
    tenants.value = await authApi.getTenants()
    return tenants.value
  }

  // The tenant travels in the X-Tenant-Code header of every request. Loading the current
  // user with the target tenant validates the membership first.
  async function switchTenant(tenantCode: string) {
    if (!session.value) throw new Error('Not signed in')
    const currentUser = await authApi.getCurrentUser(tenantCode)
    const nextSession: AuthSession = { ...session.value, ...currentUser }
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
    refreshCurrentUser,
    updateProfile,
    loadTenants,
    switchTenant,
    logout,
  }
})
