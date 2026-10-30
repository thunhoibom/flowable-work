import { describe, expect, it } from 'vitest'
import { queryKeys } from '@/api/queryKeys'

describe('tenant query keys', () => {
  it('separates cache entries by tenant', () => {
    const parameters = { pageNum: 1, pageSize: 20 }
    expect(queryKeys.processDefinitionPage('tenant-a', parameters)).not.toEqual(
      queryKeys.processDefinitionPage('tenant-b', parameters),
    )
    expect(queryKeys.processDefinitionPage('tenant-a', parameters)).toEqual([
      'tenant',
      'tenant-a',
      'process-definitions',
      'page',
      parameters,
    ])
  })
})
