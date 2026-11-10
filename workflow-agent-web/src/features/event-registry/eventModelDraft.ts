import type { EventFieldType, EventFieldView } from './api'

/**
 * Early feedback for the event model form. Mirrors EventModelRules on the server, message for
 * message; the server stays the authority.
 */

export const KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/
export const FIELD_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
const KEY_PATTERN_TEXT = '^[A-Za-z][A-Za-z0-9_-]{0,63}$'
const MAX_FIELDS = 64
const MAX_CORRELATIONS = 3
const CORRELATABLE: EventFieldType[] = ['string', 'integer', 'long']

export const FIELD_TYPES: { value: EventFieldType; label: string }[] = [
  { value: 'string', label: 'Chuỗi (string)' },
  { value: 'integer', label: 'Số nguyên (integer)' },
  { value: 'long', label: 'Số nguyên lớn (long)' },
  { value: 'double', label: 'Số thực (double)' },
  { value: 'boolean', label: 'Đúng/sai (boolean)' },
  { value: 'json', label: 'JSON' },
]

export function blankField(): EventFieldView {
  return { name: '', type: 'string', correlation: false, header: false, fullPayload: false }
}

export function validateEventModel(form: {
  key: string
  name: string
  fields: EventFieldView[]
}): string[] {
  const problems: string[] = []
  if (!KEY_PATTERN.test(form.key)) problems.push(`Mã sự kiện phải khớp ${KEY_PATTERN_TEXT}`)
  if (!form.name.trim()) problems.push('Tên sự kiện không được để trống')
  if (form.fields.length > MAX_FIELDS) problems.push(`Sự kiện có tối đa ${MAX_FIELDS} trường`)
  const seen = new Set<string>()
  let correlations = 0
  let fullPayloads = 0
  for (const field of form.fields) {
    const name = field.name ?? ''
    if (!FIELD_PATTERN.test(name)) {
      problems.push(`Tên trường không hợp lệ: ${name || '(trống)'}`)
    } else if (seen.has(name)) {
      problems.push(`Tên trường bị trùng: ${name}`)
    } else {
      seen.add(name)
    }
    if (!field.type) {
      problems.push(`Trường ${name} chưa có kiểu`)
      continue
    }
    if (field.correlation) {
      correlations++
      if (!CORRELATABLE.includes(field.type)) {
        problems.push(`Trường tương quan ${name} chỉ được có kiểu string, integer, long`)
      }
    }
    if (field.header && field.type === 'json') {
      problems.push(`Trường ${name} lấy từ header không thể có kiểu json`)
    }
    if (field.fullPayload) {
      fullPayloads++
      if (field.type !== 'json') {
        problems.push(`Trường ${name} nhận toàn bộ nội dung nên phải có kiểu json`)
      }
      if (field.correlation) {
        problems.push(`Trường ${name} không thể vừa nhận toàn bộ nội dung vừa là tương quan`)
      }
      if (field.header) {
        problems.push(`Trường ${name} không thể vừa nhận toàn bộ nội dung vừa lấy từ header`)
      }
    }
  }
  if (correlations === 0) problems.push('Sự kiện cần ít nhất một trường tương quan')
  if (correlations > MAX_CORRELATIONS) {
    problems.push(`Sự kiện có tối đa ${MAX_CORRELATIONS} trường tương quan`)
  }
  if (fullPayloads > 1) problems.push('Chỉ một trường được nhận toàn bộ nội dung')
  return problems
}

/** Changes that would break running instances or deployed processes. Empty means compatible. */
export function incompatibleChanges(published: EventFieldView[], next: EventFieldView[]): string[] {
  const after = new Map(next.map((field) => [field.name, field]))
  const before = new Set<string>()
  const problems: string[] = []
  for (const old of published) {
    before.add(old.name)
    const now = after.get(old.name)
    if (!now) {
      problems.push(`Xóa trường ${old.name}`)
      continue
    }
    if (now.type !== old.type) {
      problems.push(`Đổi kiểu trường ${old.name} từ ${old.type} sang ${now.type}`)
    }
    if (old.correlation && !now.correlation) problems.push(`Bỏ tương quan của trường ${old.name}`)
    if (!old.correlation && now.correlation) {
      problems.push(`Đặt trường ${old.name} thành tương quan`)
    }
    if (old.header !== now.header) problems.push(`Đổi nguồn header của trường ${old.name}`)
    if (old.fullPayload !== now.fullPayload) {
      problems.push(`Đổi cờ nhận toàn bộ nội dung của trường ${old.name}`)
    }
  }
  for (const field of after.values()) {
    if (!before.has(field.name) && field.correlation) {
      problems.push(`Thêm trường tương quan ${field.name}`)
    }
  }
  return problems
}

export function sameFields(left: EventFieldView[], right: EventFieldView[]) {
  return JSON.stringify(left) === JSON.stringify(right)
}
