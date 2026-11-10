import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { tableStubs } from '@/testing/tableStubs'
import RestChannelPanel from './RestChannelPanel.vue'
import {
  eventRegistryApi,
  type EventModelView,
  type RestChannelView,
  type SaveRestChannelCommand,
} from './api'

vi.mock('./api', () => ({
  eventRegistryApi: {
    events: vi.fn<() => Promise<EventModelView[]>>(),
    channels: vi.fn<() => Promise<RestChannelView[]>>(),
    saveChannel:
      vi.fn<(key: string, payload: SaveRestChannelCommand) => Promise<RestChannelView>>(),
    publishChannel: vi.fn<(key: string, revision: number) => Promise<RestChannelView>>(),
    deleteChannel: vi.fn<(key: string) => Promise<void>>(),
  },
}))

vi.mock('@/utils/confirmation', () => ({
  confirmAction: vi.fn<() => Promise<boolean>>(async () => true),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
    warning: vi.fn<(message: string) => void>(),
  },
}))

const slot = { template: '<div><slot /><slot name="footer" /></div>' }

const event: EventModelView = {
  key: 'paymentReceived',
  name: 'Đã nhận thanh toán',
  fields: [
    { name: 'orderId', type: 'string', correlation: true, header: false, fullPayload: false },
  ],
  revision: 1,
  updatedBy: 'designer',
  updatedAt: '2026-11-10T00:00:00Z',
  publishedVersion: 1,
  publishedFields: [
    { name: 'orderId', type: 'string', correlation: true, header: false, fullPayload: false },
  ],
  changed: false,
}

const channel: RestChannelView = {
  key: 'payWebhook',
  name: 'Webhook thanh toán',
  detection: 'FIXED',
  detectionValue: 'paymentReceived',
  allowedEventKeys: ['paymentReceived'],
  userIds: [],
  groupIds: [],
  revision: 1,
  updatedBy: 'designer',
  updatedAt: '2026-11-10T00:00:00Z',
  changed: true,
  webhookPath: '/platform-api/channel-definitions/key/payWebhook/events',
}

function mountPanel() {
  return mount(RestChannelPanel, {
    global: {
      directives: { loading: {} },
      stubs: {
        ...tableStubs,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-tag': { template: '<span><slot /></span>' },
        'el-select': { template: '<div data-stub="select" />' },
        'el-option': true,
        'el-dialog': slot,
        'el-form': slot,
        'el-form-item': slot,
        'el-input': true,
        ListEmptyState: slot,
      },
    },
  })
}

async function click(wrapper: ReturnType<typeof mountPanel>, action: string) {
  await wrapper.get(`[data-action="${action}"][data-channel="payWebhook"]`).trigger('click')
  await flushPromises()
}

describe('RestChannelPanel', () => {
  beforeEach(() => {
    vi.mocked(eventRegistryApi.events).mockResolvedValue([event])
    vi.mocked(eventRegistryApi.channels).mockResolvedValue([structuredClone(channel)])
  })

  it('hides the event multi-select for a fixed channel', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('[data-field="detection"]').exists()).toBe(false)
    await click(wrapper, 'edit-channel')
    expect(wrapper.find('[data-field="allowed-events"]').exists()).toBe(false)
    expect((wrapper.get('[data-field="fixed-event"]').element as HTMLSelectElement).value).toBe(
      'paymentReceived',
    )

    await wrapper.get('[data-field="detection"]').setValue('JSON_FIELD')
    expect(wrapper.find('[data-field="allowed-events"]').exists()).toBe(true)

    await wrapper.get('[data-field="detection"]').setValue('FIXED')
    expect(wrapper.find('[data-field="allowed-events"]').exists()).toBe(false)
  })

  it('requires at least one allowed caller', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await click(wrapper, 'edit-channel')

    expect(wrapper.get('[data-list="problems"]').text()).toContain(
      'Kênh cần ít nhất một người hoặc vai trò được gửi',
    )
  })

  it('shows the webhook url and a curl call with an Idempotency-Key', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('[data-field="curl"]').exists()).toBe(false)
    await click(wrapper, 'open-webhook')

    expect(wrapper.get('[data-field="webhook-url"]').text()).toContain(
      '/platform-api/channel-definitions/key/payWebhook/events',
    )
    const curl = wrapper.get('[data-field="curl"]').text()
    expect(curl).toContain('Idempotency-Key: <uuid>')
    expect(curl).toContain('"orderId":""')
  })
})
