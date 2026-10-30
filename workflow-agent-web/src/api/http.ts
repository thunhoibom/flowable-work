import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import type { ApiResponse } from '@/types/api'
import { clearAuthSession, getAccessToken } from '@/features/auth/storage'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code = 'REQUEST_FAILED',
    public readonly status?: number,
    public readonly traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const invalidResponseMessage = 'Máy chủ trả về dữ liệu không đọc được'
export const operationFailedMessage = 'Không thực hiện được'
export const missingCsrfMessage = 'Không tạo được phiên an toàn'

export function fallbackHttpMessage(status: number | undefined, transportMessage: string) {
  if (status === 401) return 'Phiên đăng nhập đã hết hạn'
  if (status === 403) return 'Bạn không có quyền làm việc này'
  return transportMessage || 'Không kết nối được. Hãy thử lại.'
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15_000,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    'Content-Type': 'application/json',
  },
})

http.interceptors.request.use((config) => {
  const accessToken = getAccessToken()
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`
  return config
})

http.interceptors.response.use(undefined, (error: AxiosError<ApiResponse<unknown>>) => {
  const body = error.response?.data
  const traceId = error.response?.headers['x-trace-id'] as string | undefined
  if (error.response?.status === 401) {
    clearAuthSession()
    window.dispatchEvent(new CustomEvent('workflow-auth:unauthorized'))
  }
  const message = body?.message || fallbackHttpMessage(error.response?.status, error.message || '')
  return Promise.reject(
    new ApiError(message, body?.code || 'HTTP_ERROR', error.response?.status, traceId),
  )
})

async function unwrap<T>(request: Promise<AxiosResponse<ApiResponse<T>>>) {
  const response = await request
  const body = response.data
  if (!body || typeof body !== 'object' || typeof body.code !== 'string') {
    throw new ApiError(invalidResponseMessage, 'INVALID_RESPONSE', response.status)
  }
  if (body.code !== 'SUCCESS') {
    throw new ApiError(body.message || operationFailedMessage, body.code, response.status)
  }
  return body.data
}

async function withCsrf(config?: AxiosRequestConfig): Promise<AxiosRequestConfig> {
  const response = await http.get<ApiResponse<string>>('/auth/csrf')
  const token = response.data?.data
  if (!token) throw new ApiError(missingCsrfMessage, 'CSRF_TOKEN_MISSING', response.status)
  return {
    ...config,
    headers: {
      ...config?.headers,
      'X-XSRF-TOKEN': token,
    },
  }
}

export const apiClient = {
  get<T>(url: string, config?: AxiosRequestConfig) {
    return unwrap(http.get<ApiResponse<T>>(url, config))
  },
  async post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return unwrap(http.post<ApiResponse<T>>(url, data, await withCsrf(config)))
  },
  async put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return unwrap(http.put<ApiResponse<T>>(url, data, await withCsrf(config)))
  },
  async patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return unwrap(http.patch<ApiResponse<T>>(url, data, await withCsrf(config)))
  },
  async delete<T>(url: string, config?: AxiosRequestConfig) {
    return unwrap(http.delete<ApiResponse<T>>(url, await withCsrf(config)))
  },
}

export function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : operationFailedMessage
}
