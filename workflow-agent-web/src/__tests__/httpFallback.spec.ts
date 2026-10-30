import { describe, expect, it } from 'vitest'
import {
  fallbackHttpMessage,
  invalidResponseMessage,
  missingCsrfMessage,
  operationFailedMessage,
} from '@/api/http'

describe('HTTP fallback copy', () => {
  it('uses Vietnamese text when the server sends no message', () => {
    expect(fallbackHttpMessage(401, 'Unauthorized')).toBe('Phiên đăng nhập đã hết hạn')
    expect(fallbackHttpMessage(403, 'Forbidden')).toBe('Bạn không có quyền làm việc này')
    expect(fallbackHttpMessage(500, '')).toBe('Không kết nối được. Hãy thử lại.')
    expect(fallbackHttpMessage(500, 'timeout of 15000ms exceeded')).toBe(
      'timeout of 15000ms exceeded',
    )
    expect(invalidResponseMessage).toBe('Máy chủ trả về dữ liệu không đọc được')
    expect(operationFailedMessage).toBe('Không thực hiện được')
    expect(missingCsrfMessage).toBe('Không tạo được phiên an toàn')
  })
})
