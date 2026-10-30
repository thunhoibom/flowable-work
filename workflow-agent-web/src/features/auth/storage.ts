import type { AuthSession } from './types'

const AUTH_STORAGE_KEY = 'workflow-agent.auth-session'

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isValidAuthSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') return false
  const session = value as Partial<AuthSession>
  const expiresAt =
    typeof session.expiresAt === 'string' ? Date.parse(session.expiresAt) : Number.NaN
  return (
    typeof session.userId === 'string' &&
    typeof session.username === 'string' &&
    (session.displayName === null || typeof session.displayName === 'string') &&
    typeof session.tenantCode === 'string' &&
    (session.accessToken === undefined || typeof session.accessToken === 'string') &&
    typeof session.tokenType === 'string' &&
    typeof session.expiresIn === 'number' &&
    Number.isFinite(session.expiresIn) &&
    Number.isFinite(expiresAt) &&
    expiresAt > Date.now() &&
    isStringArray(session.roles) &&
    isStringArray(session.permissions)
  )
}

export function readAuthSession(): AuthSession | null {
  const serialized = localStorage.getItem(AUTH_STORAGE_KEY)
  if (!serialized) return null

  try {
    const session: unknown = JSON.parse(serialized)
    if (!isValidAuthSession(session)) {
      clearAuthSession()
      return null
    }
    return session
  } catch {
    clearAuthSession()
    return null
  }
}

export function writeAuthSession(session: AuthSession) {
  // The access token is issued as an HttpOnly cookie by the API. Persist only
  // non-sensitive session metadata so a page reload can restore the UI state.
  const { accessToken: _accessToken, ...sessionMetadata } = session
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ ...sessionMetadata, accessToken: '' }))
}

export function clearAuthSession() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
}

export function getAccessToken() {
  // Browser requests authenticate through the HttpOnly cookie. Never expose
  // the bearer token to JavaScript or persist it in localStorage.
  return null
}
