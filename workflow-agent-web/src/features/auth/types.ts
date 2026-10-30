export interface AuthUser {
  userId: string
  username: string
  displayName: string | null
  tenantCode: string
  roles: string[]
  permissions: string[]
}

export interface AuthSession extends AuthUser {
  tokenType: string
  accessToken?: string
  expiresIn: number
  expiresAt: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest extends LoginRequest {
  displayName?: string
}

export interface TenantOption {
  tenantId: string
  tenantCode: string
  tenantName: string
  enabled: boolean
  current: boolean
  roles: string[]
}
