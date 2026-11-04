export interface AuthUser {
  userId: string
  username: string
  displayName: string | null
  tenantCode: string
  /** EIM role codes from the token's `eim_roles` claim. */
  roles: string[]
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

export interface TenantOption {
  tenantId: string
  tenantCode: string
  tenantName: string
  enabled: boolean
  current: boolean
}

/** One entry of the user's EIM menu (`GET /auth/sitemaps`), already filtered and ordered. */
export interface SitemapEntry {
  code: string
  name: string | null
  url: string | null
  order: number
  privileges: string[]
  children: SitemapEntry[]
}
