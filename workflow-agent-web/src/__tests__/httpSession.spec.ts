import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { apiClient, http, TENANT_HEADER } from '@/api/http'
import { readAuthSession, writeAuthSession } from '@/features/auth/storage'

const session = {
  userId: 'user-1',
  username: 'alice',
  displayName: 'Alice',
  tenantCode: 'rsa-hn',
  roles: ['USER'],
  tokenType: 'Bearer',
  expiresIn: 1800,
  expiresAt: '2099-01-01T00:00:00Z',
}

function reply(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  const response = { data, status, statusText: String(status), headers: {}, config }
  if (status >= 400) {
    const error = Object.assign(new Error(`Request failed with status ${status}`), {
      isAxiosError: true,
      config,
      response,
    })
    throw error
  }
  return response
}

describe('HTTP session handling', () => {
  const originalAdapter = http.defaults.adapter
  let calls: { url: string; tenant: unknown }[]

  beforeEach(() => {
    localStorage.clear()
    calls = []
  })

  afterEach(() => {
    http.defaults.adapter = originalAdapter
  })

  function useAdapter(handler: (config: InternalAxiosRequestConfig) => AxiosResponse) {
    http.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      calls.push({ url: config.url ?? '', tenant: config.headers.get(TENANT_HEADER) })
      return handler(config)
    }) as AxiosAdapter
  }

  it('sends the selected tenant with every request', async () => {
    writeAuthSession(session)
    useAdapter((config) => reply(config, 200, { code: 'SUCCESS', data: 'ok' }))

    await apiClient.get('/workflow/definitions')

    expect(calls[0]).toEqual({ url: '/workflow/definitions', tenant: 'rsa-hn' })
  })

  it('keeps an explicitly requested tenant', async () => {
    writeAuthSession(session)
    useAdapter((config) => reply(config, 200, { code: 'SUCCESS', data: session }))

    await apiClient.get('/auth/me', { headers: { [TENANT_HEADER]: 'default' } })

    expect(calls[0]?.tenant).toBe('default')
  })

  it('refreshes the SSO session once and replays the rejected request', async () => {
    writeAuthSession({ ...session, expiresAt: '2098-01-01T00:00:00Z' })
    let expired = true
    useAdapter((config) => {
      if (config.url === '/auth/csrf') return reply(config, 200, { code: 'SUCCESS', data: 'csrf' })
      if (config.url === '/auth/refresh') {
        expired = false
        return reply(config, 200, {
          code: 'SUCCESS',
          data: { ...session, expiresAt: '2099-06-01T00:00:00Z' },
        })
      }
      if (expired) return reply(config, 401, { code: 'UNAUTHORIZED', message: 'Bearer token expired' })
      return reply(config, 200, { code: 'SUCCESS', data: 'ok' })
    })

    await expect(apiClient.get('/workflow/definitions')).resolves.toBe('ok')

    expect(calls.map((call) => call.url)).toEqual([
      '/workflow/definitions',
      '/auth/csrf',
      '/auth/refresh',
      '/workflow/definitions',
    ])
    expect(readAuthSession()?.expiresAt).toBe('2099-06-01T00:00:00Z')
  })

  it('signs out when the session cannot be refreshed', async () => {
    writeAuthSession(session)
    const unauthorized = vi.fn()
    window.addEventListener('workflow-auth:unauthorized', unauthorized)
    useAdapter((config) => {
      if (config.url === '/auth/csrf') return reply(config, 200, { code: 'SUCCESS', data: 'csrf' })
      return reply(config, 401, { code: 'UNAUTHORIZED', message: 'Session expired' })
    })

    await expect(apiClient.get('/workflow/definitions')).rejects.toThrow('Session expired')

    expect(calls.filter((call) => call.url === '/auth/refresh')).toHaveLength(1)
    expect(unauthorized).toHaveBeenCalledTimes(1)
    expect(readAuthSession()).toBeNull()
    window.removeEventListener('workflow-auth:unauthorized', unauthorized)
  })

})
