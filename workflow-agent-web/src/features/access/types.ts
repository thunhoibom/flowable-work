export interface TenantMember {
  userId: string
  username: string
  displayName: string
  enabled: boolean
  joinedAt?: string
}

export interface DirectoryUser {
  userId: string
  username: string
  displayName: string
}
