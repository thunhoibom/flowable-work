import { beforeEach, describe, expect, it } from 'vitest'
import { readAuthSession, writeAuthSession } from '@/features/auth/storage'

const validSession = {
  userId: 'user-1',
  username: 'alice',
  displayName: 'Alice',
  tenantCode: 'default',
  roles: ['USER'],
  permissions: ['workflow:definition:read'],
  tokenType: 'Bearer',
  accessToken: 'token',
  expiresIn: 3600,
  expiresAt: '2099-01-01T00:00:00Z',
}

describe('authentication storage', () => {
  beforeEach(() => localStorage.clear())

  it('persists session metadata without the access token', () => {
    writeAuthSession(validSession)
    expect(readAuthSession()).toEqual({ ...validSession, accessToken: '' })
  })

  it('removes malformed or expired sessions', () => {
    localStorage.setItem(
      'workflow-agent.auth-session',
      JSON.stringify({ ...validSession, expiresAt: 'not-a-date' }),
    )
    expect(readAuthSession()).toBeNull()
    expect(localStorage.getItem('workflow-agent.auth-session')).toBeNull()
  })
})
