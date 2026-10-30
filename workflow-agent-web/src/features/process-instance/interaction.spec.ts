import { describe, expect, it } from 'vitest'
import { buildInteractionVariables, mergeInteractionVariables } from './interaction'
import type { InteractionDataField } from './types'

function field(overrides: Partial<InteractionDataField>): InteractionDataField {
  return {
    variablePath: 'order.customer.name',
    label: '客户名称',
    dataType: 'string',
    required: true,
    agentActivityId: 'agent-review',
    agentActivityName: 'Agent 审核',
    agentInputPath: 'customer.name',
    ...overrides,
  }
}

describe('Agent interaction variables', () => {
  it('builds nested process variables with typed values', () => {
    const fields = [
      field({}),
      field({ variablePath: 'order.amount', label: '金额', dataType: 'number' }),
      field({ variablePath: 'urgent', label: '加急', dataType: 'boolean' }),
    ]

    expect(
      buildInteractionVariables(fields, {
        'order.customer.name': '测试客户',
        'order.amount': '12.5',
        urgent: false,
      }),
    ).toEqual({ order: { customer: { name: '测试客户' }, amount: 12.5 }, urgent: false })
  })

  it('rejects missing required input before a workflow command is sent', () => {
    expect(() => buildInteractionVariables([field({})], {})).toThrow('Hãy điền “客户名称”')
  })

  it('merges generated nested values without dropping advanced variables', () => {
    expect(
      mergeInteractionVariables(
        { order: { id: 'O-1' }, operator: 'tester' },
        { order: { customer: { name: '客户' } } },
      ),
    ).toEqual({ order: { id: 'O-1', customer: { name: '客户' } }, operator: 'tester' })
  })
})
