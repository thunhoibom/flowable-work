import { describe, expect, it } from 'vitest'
import { showsApprovalMode } from './serviceTaskPanel'

describe('service task panel', () => {
  it('hides approval mode on a service task without treating every task as hidden', () => {
    expect(showsApprovalMode(true, true)).toBe(false)
    expect(showsApprovalMode(true, false)).toBe(true)
    expect(showsApprovalMode(false, false)).toBe(false)
  })
})
