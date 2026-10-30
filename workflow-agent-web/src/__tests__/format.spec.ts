import { describe, expect, it } from 'vitest'
import {
  displayValue,
  formatDateTime,
  formatDuration,
  formatVersion,
  joinValues,
  splitValues,
} from '@/utils/format'

describe('format utilities', () => {
  it('normalizes comma and line separated targets', () => {
    expect(splitValues('alice, bob\nops，reviewer')).toEqual(['alice', 'bob', 'ops', 'reviewer'])
    expect(joinValues(['alice', 'bob'])).toBe('alice, bob')
  })

  it('formats dates, versions, durations, and structured variables', () => {
    expect(formatDateTime('2026-10-28T15:04:05')).toBe('28-10-2026 15:04:05')
    expect(formatDateTime(null)).toBe('-')
    expect(formatVersion(2)).toBe('Phiên bản 2')
    expect(formatVersion(null)).toBe('-')
    expect(formatDuration(5_000)).toBe('5 giây')
    expect(formatDuration(90_000)).toBe('1 phút 30 giây')
    expect(formatDuration(3_661_000)).toBe('1 giờ 1 phút')
    expect(formatDuration(null)).toBe('-')
    expect(displayValue({ approved: true })).toBe('{"approved":true}')
  })
})
