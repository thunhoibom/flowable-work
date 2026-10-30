import { describe, expect, it } from 'vitest'
import { getAgentErrorPresentation } from './errorPresentation'

describe('agent error presentation', () => {
  it('describes a known provider timeout in Vietnamese', () => {
    expect(getAgentErrorPresentation('PROVIDER_TIMEOUT')).toMatchObject({
      code: 'PROVIDER_TIMEOUT',
      title: 'Nhà cung cấp không trả lời kịp',
      action: 'RETRY_PROVIDER',
      actionLabel: 'Có thể thử lại',
    })
  })

  it('describes an unknown code without translating the code', () => {
    expect(getAgentErrorPresentation('UNKNOWN_CODE')).toMatchObject({
      code: 'UNKNOWN_CODE',
      title: 'Agent gặp lỗi chưa rõ',
      action: 'WAIT_FOR_REVIEW',
      actionLabel: 'Cần người quản trị xem',
    })
  })

  it('returns null when there is no code', () => {
    expect(getAgentErrorPresentation(null)).toBeNull()
  })
})
