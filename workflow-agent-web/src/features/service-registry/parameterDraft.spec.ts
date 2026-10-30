import { describe, expect, it } from 'vitest'
import { pathRequirement } from './parameterDraft'

describe('parameter draft', () => {
  it('locks a path parameter as required and leaves other locations editable', () => {
    expect(pathRequirement('PATH', false)).toEqual({ required: true, locked: true })
    expect(pathRequirement('QUERY', true)).toEqual({ required: true, locked: false })
    expect(pathRequirement('BODY', false)).toEqual({ required: false, locked: false })
  })
})
