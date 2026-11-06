import { ApiError } from '@/api/http'
import type { ProcessDraft } from './types'

export interface DesignerStatus {
  status: 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'
  label: string
  canPublish: boolean
  canSuspend: boolean
  canResume: boolean
}

/** Lifecycle of a process as the designer shows it. Running = latest version, not suspended. */
export function designerStatus(draft: ProcessDraft): DesignerStatus {
  if (!draft.latestVersion) {
    return {
      status: 'DRAFT',
      label: 'Chỉ có bản nháp',
      canPublish: true,
      canSuspend: false,
      canResume: false,
    }
  }
  const pending = draft.hasUnpublishedChanges ? ' · có thay đổi chưa phát hành' : ''
  if (draft.suspended) {
    return {
      status: 'SUSPENDED',
      label: `Đã ngừng v${draft.latestVersion}${pending}`,
      canPublish: draft.hasUnpublishedChanges,
      canSuspend: false,
      canResume: true,
    }
  }
  return {
    status: 'PUBLISHED',
    label: `Đang chạy v${draft.latestVersion}${pending}`,
    canPublish: draft.hasUnpublishedChanges,
    canSuspend: true,
    canResume: false,
  }
}

/** Another session saved the draft first. The caller should offer reload or overwrite. */
export function isDraftConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'DRAFT_REVISION_CONFLICT'
}
