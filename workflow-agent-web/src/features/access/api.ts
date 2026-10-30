import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'
import type {
  DirectoryUser,
  PermissionItem,
  SaveRoleCommand,
  TenantMember,
  TenantRole,
} from './types'

const ROOT = '/auth/access'

export const accessApi = {
  directoryUsers: (params: { keyword?: string; pageNum: number; pageSize: number }) =>
    apiClient.get<PageResult<DirectoryUser>>('/auth/directory/users', { params }),
  members: (keyword?: string) =>
    apiClient.get<TenantMember[]>(`${ROOT}/members`, { params: { keyword } }),
  addMember: (username: string, roleCodes: string[]) =>
    apiClient.post<TenantMember>(`${ROOT}/members`, { username, roleCodes }),
  updateMemberRoles: (userId: string, roleCodes: string[]) =>
    apiClient.post<void>(`${ROOT}/members/${userId}/roles`, { roleCodes }),
  updateMemberEnabled: (userId: string, enabled: boolean) =>
    apiClient.post<void>(`${ROOT}/members/${userId}/enabled`, undefined, {
      params: { enabled },
    }),
  roles: () => apiClient.get<TenantRole[]>(`${ROOT}/roles`),
  saveRole: (payload: SaveRoleCommand) => apiClient.post<TenantRole>(`${ROOT}/roles`, payload),
  permissions: () => apiClient.get<PermissionItem[]>(`${ROOT}/permissions`),
}
