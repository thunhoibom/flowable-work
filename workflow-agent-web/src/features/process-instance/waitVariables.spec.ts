import { describe, expect, it } from 'vitest'
import { parseWaitVariables } from './waitVariables'

describe('parseWaitVariables', () => {
  it('accepts empty text and JSON objects', () => {
    expect(parseWaitVariables('')).toEqual({})
    expect(parseWaitVariables('  ')).toEqual({})
    expect(parseWaitVariables('{"approved": true, "note": "ok"}')).toEqual({
      approved: true,
      note: 'ok',
    })
  })

  it('rejects anything that is not a JSON object', () => {
    expect(() => parseWaitVariables('[1]')).toThrow('đối tượng JSON')
    expect(() => parseWaitVariables('42')).toThrow('đối tượng JSON')
    expect(() => parseWaitVariables('null')).toThrow('đối tượng JSON')
    expect(() => parseWaitVariables('{oops')).toThrow('đối tượng JSON')
  })
})
