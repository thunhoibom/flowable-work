import dayjs from 'dayjs'

export function formatDateTime(value?: string | null) {
  return value ? dayjs(value).format('DD-MM-YYYY HH:mm:ss') : '-'
}

export function formatVersion(value?: number | null) {
  return value === null || value === undefined ? '-' : `Phiên bản ${value}`
}

export function formatDuration(value?: number | null) {
  if (value === null || value === undefined) return '-'
  const seconds = Math.floor(value / 1000)
  if (seconds < 60) return `${seconds} giây`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} phút ${seconds % 60} giây`
  const hours = Math.floor(minutes / 60)
  return `${hours} giờ ${minutes % 60} phút`
}

export function splitValues(value: string) {
  return value
    .split(/[\n,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

export function joinValues(values?: string[]) {
  return values?.join(', ') || ''
}

export function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
