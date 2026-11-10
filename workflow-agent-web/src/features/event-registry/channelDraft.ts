import type { EventFieldView, KeyDetection } from './api'
import { FIELD_PATTERN, KEY_PATTERN } from './eventModelDraft'

/**
 * Early feedback for the channel form. Mirrors RestChannelRules on the server, message for
 * message; the server stays the authority.
 */

const KEY_PATTERN_TEXT = '^[A-Za-z][A-Za-z0-9_-]{0,63}$'
const MAX_PRINCIPALS = 50
const MAX_PRINCIPAL_LENGTH = 128

export const KEY_DETECTIONS: { value: KeyDetection; label: string }[] = [
  { value: 'FIXED', label: 'Cố định' },
  { value: 'JSON_FIELD', label: 'Trường JSON' },
  { value: 'JSON_POINTER', label: 'JSON Pointer' },
]

export interface ChannelForm {
  key: string
  name: string
  detection: KeyDetection | ''
  detectionValue: string
  allowedEventKeys: string[]
  userIds: string[]
  groupIds: string[]
}

/** The events the channel accepts: with Cố định, only the fixed one. */
export function allowedEvents(
  form: Pick<ChannelForm, 'detection' | 'detectionValue' | 'allowedEventKeys'>,
) {
  if (form.detection === 'FIXED') {
    return form.detectionValue.trim() ? [form.detectionValue.trim()] : []
  }
  return [...new Set(form.allowedEventKeys.map((key) => key.trim()))]
}

export function validateChannel(form: ChannelForm): string[] {
  const problems: string[] = []
  if (!KEY_PATTERN.test(form.key)) problems.push(`Mã kênh phải khớp ${KEY_PATTERN_TEXT}`)
  if (!form.name.trim()) problems.push('Tên kênh không được để trống')
  const value = form.detectionValue.trim()
  if (!form.detection) {
    problems.push('Chưa chọn cách nhận diện sự kiện')
  } else if (!value) {
    problems.push('Chưa nhập giá trị nhận diện sự kiện')
  } else if (form.detection === 'FIXED' && !KEY_PATTERN.test(value)) {
    problems.push(`Mã sự kiện cố định không hợp lệ: ${value}`)
  } else if (form.detection === 'JSON_FIELD' && !FIELD_PATTERN.test(value)) {
    problems.push(`Trường JSON không hợp lệ: ${value}`)
  } else if (form.detection === 'JSON_POINTER' && !value.startsWith('/')) {
    problems.push('JSON Pointer phải bắt đầu bằng /')
  }
  const allowed = allowedEvents(form)
  if (!allowed.length) problems.push('Kênh cần nhận ít nhất một sự kiện')
  for (const key of allowed) {
    if (!KEY_PATTERN.test(key)) problems.push(`Mã sự kiện không hợp lệ: ${key}`)
  }
  const users = unique(form.userIds)
  const groups = unique(form.groupIds)
  if (!users.length && !groups.length) {
    problems.push('Kênh cần ít nhất một người hoặc vai trò được gửi')
  }
  principals(users, 'người', problems)
  principals(groups, 'vai trò', problems)
  return problems
}

function unique(values: string[]) {
  return [...new Set(values.map((value) => value.trim()))]
}

function principals(values: string[], label: string, problems: string[]) {
  if (values.length > MAX_PRINCIPALS) problems.push(`Tối đa ${MAX_PRINCIPALS} ${label} được gửi`)
  for (const value of values) {
    if (!value) problems.push(`Mã ${label} được gửi không được để trống`)
    else if (value.length > MAX_PRINCIPAL_LENGTH) {
      problems.push(`Mã ${label} được gửi dài quá ${MAX_PRINCIPAL_LENGTH} ký tự`)
    }
  }
}

export function webhookUrl(origin: string, path: string) {
  return `${origin.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

/** A sample body built from the event's fields: "" for text, 0 for numbers, false, {}. */
export function sampleBody(fields: EventFieldView[]) {
  const body: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.header || field.fullPayload) continue
    body[field.name] =
      field.type === 'string'
        ? ''
        : field.type === 'boolean'
          ? false
          : field.type === 'json'
            ? {}
            : 0
  }
  return body
}

export function curlExample(url: string, body: Record<string, unknown>) {
  const json = JSON.stringify(body).replace(/'/g, `'\\''`)
  return [
    `curl -X POST '${url}'`,
    `  -H 'Authorization: Bearer <token>'`,
    `  -H 'X-Tenant-Code: <tenant>'`,
    `  -H 'Idempotency-Key: <uuid>'`,
    `  -H 'Content-Type: application/json'`,
    `  -d '${json}'`,
  ].join(' \\\n')
}
