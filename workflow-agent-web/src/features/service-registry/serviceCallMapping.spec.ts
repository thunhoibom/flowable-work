import { describe, expect, it } from 'vitest'
import {
  buildInputRows,
  buildOutputRows,
  missingRequired,
  normalizeOutputVariable,
} from './serviceCallMapping'

describe('service call mapping', () => {
  it('fills defaults and keeps a saved blank expression when the name matches', () => {
    const rows = buildInputRows(
      [
        { name: 'customerName', type: 'STRING', required: true, location: 'BODY' },
        { name: 'orderId', type: 'STRING', required: true, location: 'PATH' },
      ],
      [{ name: 'customerName', expression: '' }],
    )
    expect(rows.map((row) => row.expression)).toEqual(['', '${orderId}'])
  })

  it('drops names that are not on the next operation', () => {
    const rows = buildOutputRows(
      [{ name: 'id', type: 'LONG', jsonField: 'id' }],
      [{ name: 'gone', variable: 'old' }],
    )
    expect(rows).toEqual([{ name: 'id', type: 'LONG', jsonField: 'id', variable: 'id' }])
  })

  it('reports empty required expressions and repairs variable names', () => {
    expect(missingRequired([{ name: 'orderId', required: true, expression: '  ' }])).toEqual(['orderId'])
    expect(normalizeOutputVariable('lead-id', 'leadId', 'id')).toBe('leadId')
    expect(normalizeOutputVariable('lead-id', 'bad-name', 'id')).toBe('id')
    expect(normalizeOutputVariable('leadId', 'id', 'id')).toBe('leadId')
  })
})
