import { describe, expect, it } from 'vitest'
import { normalizeProcessIdentity } from '@/features/process-definition/domain'

describe('process definition identity', () => {
  it('normalizes a valid identity', () => {
    expect(normalizeProcessIdentity(' expense_approval ', ' 费用审批 ')).toEqual({
      key: 'expense_approval',
      name: '费用审批',
    })
  })

  it.each(['1approval', 'approval space', '_approval'])('rejects invalid key %s', (key) => {
    expect(() => normalizeProcessIdentity(key, '审批')).toThrow(
      'Mã quy trình phải bắt đầu bằng chữ cái',
    )
  })

  it('rejects an empty identity', () => {
    expect(() => normalizeProcessIdentity('  ', '  ')).toThrow(
      'Mã quy trình và tên quy trình không được để trống',
    )
  })
})
