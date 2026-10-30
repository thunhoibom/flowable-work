export interface TenantMember {
  userId: string
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  globalRoles: string[]
  joinedAt?: string
}

export interface DirectoryUser {
  userId: string
  username: string
  displayName: string
}

export interface TenantRole {
  roleCode: string
  roleName: string
  description?: string
  enabled: boolean
  builtIn: boolean
  permissions: string[]
}

export interface PermissionItem {
  permissionCode: string
  permissionName: string
  description?: string
}

export interface SaveRoleCommand {
  roleCode: string
  roleName: string
  description?: string
  enabled: boolean
  permissions: string[]
}
