import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type { DirectoryUser, TenantMember } from './types'

const ROOT = '/auth/access'

/** Tenant membership only: roles are assigned in EIM. */
export const accessApi = {
  directoryUsers: (params: { keyword?: string; pageNum: number; pageSize: number }) =>
    apiClient.get<PageResult<DirectoryUser>>('/auth/directory/users', { params }),
  members: (keyword?: string) =>
    apiClient.get<TenantMember[]>(`${ROOT}/members`, { params: { keyword } }),
  addMember: (username: string) => apiClient.post<TenantMember>(`${ROOT}/members`, { username }),
  updateMemberEnabled: (userId: string, enabled: boolean) =>
    apiClient.post<void>(`${ROOT}/members/${userId}/enabled`, undefined, {
      params: { enabled },
    }),
}
