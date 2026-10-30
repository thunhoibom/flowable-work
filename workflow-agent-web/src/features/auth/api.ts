import { apiClient } from '@/api/http'
import type { AuthSession, AuthUser, LoginRequest, RegisterRequest, TenantOption } from './types'

export function login(request: LoginRequest) {
  return apiClient.post<AuthSession>('/auth/login', request)
}

export function ensureCsrfToken() {
  return apiClient.get<string>('/auth/csrf')
}

export function register(request: RegisterRequest) {
  return apiClient.post<AuthSession>('/auth/register', request)
}

export function getCurrentUser() {
  return apiClient.get<AuthUser>('/auth/me')
}

export function updateProfile(displayName: string) {
  return apiClient.patch<AuthUser>('/auth/me', { displayName })
}

export function changePassword(currentPassword: string, newPassword: string) {
  return apiClient.post<void>('/auth/me/password', { currentPassword, newPassword })
}

export function getTenants() {
  return apiClient.get<TenantOption[]>('/auth/tenants')
}

export function switchTenant(tenantCode: string) {
  return apiClient.post<AuthSession>('/auth/switch-tenant', { tenantCode })
}

export function logout() {
  return apiClient.post<void>('/auth/logout')
}
