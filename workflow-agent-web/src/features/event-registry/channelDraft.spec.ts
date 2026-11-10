import { describe, expect, it } from 'vitest'
import {
  allowedEvents,
  curlExample,
  sampleBody,
  validateChannel,
  webhookUrl,
  type ChannelForm,
} from './channelDraft'

// Same cases and messages as RestChannelRulesTest on the server.
const channel = (overrides: Partial<ChannelForm>): ChannelForm => ({
  key: 'payWebhook',
  name: 'Webhook',
  detection: 'FIXED',
  detectionValue: 'paymentReceived',
  allowedEventKeys: [],
  userIds: ['erp-client'],
  groupIds: [],
  ...overrides,
})

describe('channel rules', () => {
  it('accepts a valid channel', () => {
    expect(validateChannel(channel({}))).toEqual([])
    expect(
      validateChannel(
        channel({
          detection: 'JSON_POINTER',
          detectionValue: '/meta/type',
          allowedEventKeys: ['paymentReceived', 'refundIssued'],
          userIds: [],
          groupIds: ['WF_ADMIN'],
        }),
      ),
    ).toEqual([])
  })

  it('lets a fixed channel accept only its own event', () => {
    expect(allowedEvents(channel({ allowedEventKeys: ['other'] }))).toEqual(['paymentReceived'])
  })

  it('reports every problem', () => {
    expect(
      validateChannel({
        key: '1bad',
        name: ' ',
        detection: 'JSON_FIELD',
        detectionValue: 'not a field',
        allowedEventKeys: [],
        userIds: [],
        groupIds: [],
      }),
    ).toEqual([
      'Mã kênh phải khớp ^[A-Za-z][A-Za-z0-9_-]{0,63}$',
      'Tên kênh không được để trống',
      'Trường JSON không hợp lệ: not a field',
      'Kênh cần nhận ít nhất một sự kiện',
      'Kênh cần ít nhất một người hoặc vai trò được gửi',
    ])
    expect(
      validateChannel(
        channel({
          detection: 'JSON_POINTER',
          detectionValue: 'meta/type',
          allowedEventKeys: ['ok', '9bad'],
        }),
      ),
    ).toEqual(['JSON Pointer phải bắt đầu bằng /', 'Mã sự kiện không hợp lệ: 9bad'])
    expect(validateChannel(channel({ detection: '', detectionValue: '' }))).toContain(
      'Chưa chọn cách nhận diện sự kiện',
    )
  })

  it('bounds the allowed callers', () => {
    expect(
      validateChannel(channel({ userIds: Array.from({ length: 51 }, (_, index) => `u${index}`) })),
    ).toContain('Tối đa 50 người được gửi')
    expect(validateChannel(channel({ userIds: [' ', 'x'.repeat(129)] }))).toEqual([
      'Mã người được gửi không được để trống',
      'Mã người được gửi dài quá 128 ký tự',
    ])
  })
})

describe('webhook help', () => {
  it('joins origin and path with one slash', () => {
    expect(
      webhookUrl('https://wf.example/', '/platform-api/channel-definitions/key/a/events'),
    ).toBe('https://wf.example/platform-api/channel-definitions/key/a/events')
  })

  it('builds a sample body and a curl call with an Idempotency-Key', () => {
    const body = sampleBody([
      { name: 'orderId', type: 'string', correlation: true, header: false, fullPayload: false },
      { name: 'amount', type: 'double', correlation: false, header: false, fullPayload: false },
      { name: 'paid', type: 'boolean', correlation: false, header: false, fullPayload: false },
      { name: 'meta', type: 'json', correlation: false, header: false, fullPayload: false },
      { name: 'source', type: 'string', correlation: false, header: true, fullPayload: false },
    ])
    expect(body).toEqual({ orderId: '', amount: 0, paid: false, meta: {} })

    const curl = curlExample('https://wf.example/x', body)
    expect(curl).toContain("curl -X POST 'https://wf.example/x'")
    expect(curl).toContain("-H 'Idempotency-Key: <uuid>'")
    expect(curl).toContain("-H 'Authorization: Bearer <token>'")
    expect(curl).toContain(`-d '{"orderId":"","amount":0,"paid":false,"meta":{}}'`)
  })
})
