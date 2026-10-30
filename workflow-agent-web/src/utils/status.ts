export type StatusTone = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export interface StatusPresentation {
  label: string
  tone: StatusTone
}

const STATUS_PRESENTATIONS: Record<string, StatusPresentation> = {
  ENABLED: { label: 'Đang bật', tone: 'success' },
  DISABLED: { label: 'Đã tắt', tone: 'info' },
  DRAFT: { label: 'Bản nháp', tone: 'warning' },
  PUBLISHED: { label: 'Đã phát hành', tone: 'success' },
  QUEUED: { label: 'Đang chờ', tone: 'primary' },
  PENDING: { label: 'Sắp chạy', tone: 'info' },
  ACTIVE: { label: 'Cần làm', tone: 'primary' },
  RUNNING: { label: 'Đang chạy', tone: 'primary' },
  SUCCEEDED: { label: 'Thành công', tone: 'success' },
  SUCCESS: { label: 'Thành công', tone: 'success' },
  COMPLETED: { label: 'Đã hoàn thành', tone: 'success' },
  FAILED: { label: 'Thất bại', tone: 'danger' },
  TIMED_OUT: { label: 'Quá hạn', tone: 'danger' },
  CANCELLED: { label: 'Đã hủy', tone: 'info' },
  SKIPPED: { label: 'Đã bỏ qua', tone: 'info' },
  EMPTY: { label: 'Không có kết quả', tone: 'warning' },
  PARTIAL: { label: 'Hoàn thành một phần', tone: 'warning' },
  REJECTED: { label: 'Đã từ chối', tone: 'danger' },
  WAITING_FOR_REVIEW: { label: 'Chờ xem xét', tone: 'warning' },
  RETRYING: { label: 'Sẽ chạy lại', tone: 'primary' },
  DECIDED: { label: 'Đã quyết định', tone: 'success' },
}

export function getStatusPresentation(status: string | boolean): StatusPresentation {
  const normalized = typeof status === 'boolean' ? (status ? 'ENABLED' : 'DISABLED') : status
  return STATUS_PRESENTATIONS[normalized] ?? { label: normalized || 'Không rõ', tone: 'info' }
}

export function getStatusLabel(status: string | boolean) {
  return getStatusPresentation(status).label
}
