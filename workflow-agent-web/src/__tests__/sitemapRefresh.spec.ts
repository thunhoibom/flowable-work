import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { getSitemap } from '@/features/auth/api'
import { writeAuthSession } from '@/features/auth/storage'

const session = {
  userId: 'user-1',
  username: 'alice',
  displayName: 'Alice',
  tenantCode: 'default',
  roles: ['WF_PLATFORM_ADMIN'],
  tokenType: 'Bearer',
  expiresIn: 1800,
  expiresAt: '2099-01-01T00:00:00Z',
}

const menu = [{ code: 'WF', name: 'Workflow', url: '/', order: 1, privileges: [], children: [] }]
const eimDown = { code: 'EIM_UNAVAILABLE', message: 'Không tải được menu từ EIM' }

function reply(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  const response = { data, status, statusText: String(status), headers: {}, config }
  if (status >= 400) {
    throw Object.assign(new Error(`Request failed with status ${status}`), {
      isAxiosError: true,
      config,
      response,
    })
  }
  return response
}

describe('EIM sitemap', () => {
  const originalAdapter = http.defaults.adapter
  let calls: string[]

  beforeEach(() => {
    localStorage.clear()
    writeAuthSession(session)
    calls = []
  })

  afterEach(() => {
    http.defaults.adapter = originalAdapter
  })

  function useAdapter(handler: (config: InternalAxiosRequestConfig) => AxiosResponse) {
    http.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      calls.push(config.url ?? '')
      return handler(config)
    }) as AxiosAdapter
  }

  function session200(config: InternalAxiosRequestConfig) {
    if (config.url === '/auth/csrf') return reply(config, 200, { code: 'SUCCESS', data: 'csrf' })
    return reply(config, 200, { code: 'SUCCESS', data: session })
  }

  it('renews the SSO session once and asks EIM again', async () => {
    let refreshed = false
    useAdapter((config) => {
      if (config.url === '/auth/refresh') refreshed = true
      if (config.url !== '/auth/sitemaps') return session200(config)
      return refreshed
        ? reply(config, 200, { code: 'SUCCESS', data: menu })
        : reply(config, 503, eimDown)
    })

    await expect(getSitemap()).resolves.toEqual(menu)

    expect(calls).toEqual(['/auth/sitemaps', '/auth/csrf', '/auth/refresh', '/auth/sitemaps'])
  })

  it('reports EIM as unavailable when it still fails after the refresh', async () => {
    useAdapter((config) =>
      config.url === '/auth/sitemaps' ? reply(config, 503, eimDown) : session200(config),
    )

    await expect(getSitemap()).rejects.toMatchObject({ code: 'EIM_UNAVAILABLE' })

    expect(calls.filter((url) => url === '/auth/sitemaps')).toHaveLength(2)
    expect(calls.filter((url) => url === '/auth/refresh')).toHaveLength(1)
  })

  it('does not refresh for other errors', async () => {
    useAdapter((config) => reply(config, 403, { code: 'FORBIDDEN', message: 'Forbidden' }))

    await expect(getSitemap()).rejects.toMatchObject({ code: 'FORBIDDEN' })

    expect(calls).toEqual(['/auth/sitemaps'])
  })
})
