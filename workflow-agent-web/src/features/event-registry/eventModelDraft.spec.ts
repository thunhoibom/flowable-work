import { describe, expect, it } from 'vitest'
import type { EventFieldType, EventFieldView } from './api'
import { incompatibleChanges, validateEventModel } from './eventModelDraft'

// Same cases and messages as EventModelRulesTest on the server.
const field = (name: string, type: EventFieldType): EventFieldView => ({
  name,
  type,
  correlation: false,
  header: false,
  fullPayload: false,
})
const correlation = (name: string, type: EventFieldType): EventFieldView => ({
  ...field(name, type),
  correlation: true,
})
const fullPayload = (name: string, type: EventFieldType, header = false): EventFieldView => ({
  ...field(name, type),
  header,
  fullPayload: true,
})
const model = (...fields: EventFieldView[]) => ({
  key: 'paymentReceived',
  name: 'Đã nhận thanh toán',
  fields,
})

describe('event model rules', () => {
  it('accepts a valid model', () => {
    expect(
      validateEventModel(
        model(
          correlation('orderId', 'string'),
          field('amount', 'double'),
          fullPayload('raw', 'json'),
        ),
      ),
    ).toEqual([])
  })

  it('reports every structural problem', () => {
    const problems = validateEventModel({ key: '1bad', name: ' ', fields: [] })
    expect(problems).toEqual([
      'Mã sự kiện phải khớp ^[A-Za-z][A-Za-z0-9_-]{0,63}$',
      'Tên sự kiện không được để trống',
      'Sự kiện cần ít nhất một trường tương quan',
    ])
    const tooMany = Array.from({ length: 65 }, (_, index) => field(`f${index}`, 'string'))
    expect(validateEventModel({ key: 'ok', name: 'Ok', fields: tooMany })).toContain(
      'Sự kiện có tối đa 64 trường',
    )
  })

  it('limits correlation fields in number and type', () => {
    expect(validateEventModel(model(correlation('a', 'double')))).toContain(
      'Trường tương quan a chỉ được có kiểu string, integer, long',
    )
    expect(
      validateEventModel(
        model(
          correlation('a', 'string'),
          correlation('b', 'string'),
          correlation('c', 'long'),
          correlation('d', 'integer'),
        ),
      ),
    ).toContain('Sự kiện có tối đa 3 trường tương quan')
  })

  it('checks full payload, headers and names', () => {
    expect(
      validateEventModel(model(correlation('id', 'string'), fullPayload('raw', 'string'))),
    ).toContain('Trường raw nhận toàn bộ nội dung nên phải có kiểu json')
    expect(
      validateEventModel(
        model(correlation('id', 'string'), fullPayload('a', 'json'), fullPayload('b', 'json')),
      ),
    ).toContain('Chỉ một trường được nhận toàn bộ nội dung')
    expect(
      validateEventModel(model(correlation('id', 'string'), fullPayload('raw', 'json', true))),
    ).toEqual(
      expect.arrayContaining([
        'Trường raw lấy từ header không thể có kiểu json',
        'Trường raw không thể vừa nhận toàn bộ nội dung vừa lấy từ header',
      ]),
    )
    expect(
      validateEventModel(
        model(correlation('id', 'string'), field('id', 'string'), field('9x', 'string')),
      ),
    ).toEqual(expect.arrayContaining(['Tên trường bị trùng: id', 'Tên trường không hợp lệ: 9x']))
  })

  it('treats only adding plain fields as compatible', () => {
    const published = [correlation('orderId', 'string'), field('amount', 'double')]

    expect(incompatibleChanges(published, [...published, field('note', 'string')])).toEqual([])
    expect(incompatibleChanges(published, [correlation('orderId', 'string')])).toEqual([
      'Xóa trường amount',
    ])
    expect(
      incompatibleChanges(published, [correlation('orderId', 'string'), field('amount', 'long')]),
    ).toEqual(['Đổi kiểu trường amount từ double sang long'])
    expect(
      incompatibleChanges(published, [field('orderId', 'string'), field('amount', 'double')]),
    ).toEqual(['Bỏ tương quan của trường orderId'])
    expect(
      incompatibleChanges(published, [...published, correlation('customerId', 'string')]),
    ).toEqual(['Thêm trường tương quan customerId'])
  })
})
