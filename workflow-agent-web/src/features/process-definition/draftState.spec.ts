import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/http'
import { designerStatus, isDraftConflict } from './draftState'
import type { ProcessDraft } from './types'

const draft: ProcessDraft = {
  processDefinitionKey: 'k',
  name: 'K',
  bpmnXml: '',
  revision: 1,
  suspended: false,
  hasUnpublishedChanges: true,
}

describe('designerStatus', () => {
  it('is draft-only before the first publish', () => {
    expect(designerStatus({ ...draft, latestVersion: undefined })).toEqual({
      status: 'DRAFT',
      label: 'Chỉ có bản nháp',
      canPublish: true,
      canSuspend: false,
      canResume: false,
    })
  })

  it('shows the running version and pending changes', () => {
    expect(designerStatus({ ...draft, latestVersion: 3 })).toMatchObject({
      status: 'PUBLISHED',
      label: 'Đang chạy v3 · có thay đổi chưa phát hành',
      canPublish: true,
      canSuspend: true,
      canResume: false,
    })
  })

  it('blocks publishing an unchanged running draft', () => {
    const status = designerStatus({ ...draft, latestVersion: 3, hasUnpublishedChanges: false })
    expect(status.canPublish).toBe(false)
    expect(status.label).toBe('Đang chạy v3')
  })

  it('offers resume for a suspended process, and publish only when the draft changed', () => {
    expect(
      designerStatus({ ...draft, latestVersion: 2, suspended: true, hasUnpublishedChanges: false }),
    ).toMatchObject({ status: 'SUSPENDED', canPublish: false, canResume: true, canSuspend: false })
    expect(designerStatus({ ...draft, latestVersion: 2, suspended: true }).canPublish).toBe(true)
  })
})

describe('isDraftConflict', () => {
  it('recognises the revision conflict code only', () => {
    expect(isDraftConflict(new ApiError('x', 'DRAFT_REVISION_CONFLICT', 409))).toBe(true)
    expect(isDraftConflict(new ApiError('x', 'CONFLICT', 409))).toBe(false)
    expect(isDraftConflict(new Error('x'))).toBe(false)
  })
})
