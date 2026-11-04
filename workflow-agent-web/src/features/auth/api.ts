import { apiClient, TENANT_HEADER } from '@/api/http'
import type { AuthSession, AuthUser, LoginRequest, SitemapEntry, TenantOption } from './types'

export function login(request: LoginRequest) {
  return apiClient.post<AuthSession>('/auth/login', request)
}

export function ensureCsrfToken() {
  return apiClient.get<string>('/auth/csrf')
}

/** Current user; with `tenantCode` the request also validates membership of that tenant. */
export function getCurrentUser(tenantCode?: string) {
  return apiClient.get<AuthUser>(
    '/auth/me',
    tenantCode ? { headers: { [TENANT_HEADER]: tenantCode } } : undefined,
  )
}

export function updateProfile(displayName: string) {
  return apiClient.patch<AuthUser>('/auth/me', { displayName })
}

export function getTenants() {
  return apiClient.get<TenantOption[]>('/auth/tenants')
}

/** The user's menu, managed in EIM (like rsa-core-service `/auth/sitemaps`). */
export function getSitemap() {
  return apiClient.get<SitemapEntry[]>('/auth/sitemaps')
}

export function logout() {
  return apiClient.post<void>('/auth/logout')
}
