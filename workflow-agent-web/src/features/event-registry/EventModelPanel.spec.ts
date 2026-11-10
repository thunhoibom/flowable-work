import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { tableStubs } from '@/testing/tableStubs'
import EventModelPanel from './EventModelPanel.vue'
import {
  eventRegistryApi,
  type EventModelView,
  type EventUsagesView,
  type SaveEventModelCommand,
} from './api'

vi.mock('./api', () => ({
  eventRegistryApi: {
    events: vi.fn<() => Promise<EventModelView[]>>(),
    saveEvent: vi.fn<(key: string, payload: SaveEventModelCommand) => Promise<EventModelView>>(),
    publishEvent: vi.fn<(key: string, revision: number) => Promise<EventModelView>>(),
    deleteEvent: vi.fn<(key: string) => Promise<void>>(),
    usages: vi.fn<(key: string) => Promise<EventUsagesView>>(),
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
const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

const orderId = {
  name: 'orderId',
  type: 'string',
  correlation: true,
  header: false,
  fullPayload: false,
} as const
const amount = {
  name: 'amount',
  type: 'double',
  correlation: false,
  header: false,
  fullPayload: false,
} as const

const published: EventModelView = {
  key: 'paymentReceived',
  name: 'Đã nhận thanh toán',
  fields: [{ ...orderId }, { ...amount }],
  revision: 2,
  updatedBy: 'designer',
  updatedAt: '2026-11-10T00:00:00Z',
  publishedVersion: 1,
  publishedFields: [{ ...orderId }, { ...amount }],
  changed: false,
}

function mountPanel(props: { readOnly?: boolean } = {}) {
  return mount(EventModelPanel, {
    props,
    global: {
      directives: { loading: {} },
      stubs: {
        ...tableStubs,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-tag': { template: '<span><slot /></span>' },
        'el-dialog': slot,
        'el-form': slot,
        'el-form-item': slot,
        'el-input': inputStub,
        ListEmptyState: slot,
      },
    },
  })
}

async function openEvent(wrapper: ReturnType<typeof mountPanel>, key = 'paymentReceived') {
  await wrapper.get(`[data-action="edit-event"][data-event="${key}"]`).trigger('click')
  await flushPromises()
}

describe('EventModelPanel', () => {
  beforeEach(() => {
    vi.mocked(eventRegistryApi.events).mockResolvedValue([structuredClone(published)])
    vi.mocked(eventRegistryApi.usages).mockResolvedValue({ usages: [], waiting: 0 })
  })

  it('shows the published version and adds a field row', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('[data-column="STT"]').text()).toBe('1')
    expect(wrapper.find('[data-row="field"]').exists()).toBe(false)

    await openEvent(wrapper)
    expect(wrapper.text()).toContain('Đã phát hành v1')
    expect(wrapper.findAll('[data-row="field"]')).toHaveLength(2)
    await wrapper.get('[data-action="add-field"]').trigger('click')
    expect(wrapper.findAll('[data-row="field"]')).toHaveLength(3)
  })

  it('cannot save without a correlation field', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await openEvent(wrapper)

    await wrapper.get('[data-field="field-correlation"]').setValue(false)

    expect(wrapper.get('[data-action="save-event"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-list="problems"]').text()).toContain(
      'Sự kiện cần ít nhất một trường tương quan',
    )
  })

  it('lists incompatible changes when a published field is removed', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await openEvent(wrapper)
    expect(wrapper.find('[data-list="incompatibilities"]').exists()).toBe(false)

    await wrapper.findAll('[data-action="remove-field"]')[1]!.trigger('click')

    const list = wrapper.get('[data-list="incompatibilities"]')
    expect(list.text()).toContain('Xóa trường amount')
    expect(list.text()).toContain('Hãy tạo mã sự kiện mới')
    expect(wrapper.get('[data-action="publish-event"]').attributes('disabled')).toBeDefined()
  })

  it('saves a new event with revision 0', async () => {
    vi.mocked(eventRegistryApi.events).mockResolvedValue([])
    vi.mocked(eventRegistryApi.saveEvent).mockResolvedValue({ ...published, key: 'refundIssued' })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-action="create-event"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-list="problems"]').exists()).toBe(false)
    await wrapper.get('[data-field="event-key"]').setValue('refundIssued')
    await wrapper.get('[data-field="event-name"]').setValue('Đã hoàn tiền')
    await wrapper.get('[data-field="field-name"]').setValue('refundId')
    await wrapper.get('[data-action="save-event"]').trigger('click')
    await flushPromises()

    expect(eventRegistryApi.saveEvent).toHaveBeenCalledWith('refundIssued', {
      revision: 0,
      name: 'Đã hoàn tiền',
      description: undefined,
      fields: [
        { name: 'refundId', type: 'string', correlation: true, header: false, fullPayload: false },
      ],
    })
  })

  it('hides every write button from a viewer', async () => {
    const wrapper = mountPanel({ readOnly: true })
    await flushPromises()
    await openEvent(wrapper)

    for (const action of [
      'create-event',
      'save-event',
      'publish-event',
      'delete-event',
      'add-field',
      'remove-field',
    ]) {
      expect(wrapper.find(`[data-action="${action}"]`).exists()).toBe(false)
    }
    expect(wrapper.text()).toContain('Đã phát hành v1')
  })

  it('lists where the event is used and warns on a dirty draft (R-E4)', async () => {
    vi.mocked(eventRegistryApi.usages).mockResolvedValue({
      usages: [{ processDefinitionKey: 'orders', version: 3, activityIds: ['pay', 'refund'] }],
      waiting: 2,
    })
    const wrapper = mountPanel()
    await flushPromises()

    vi.mocked(eventRegistryApi.usages).mockClear()
    await openEvent(wrapper)
    expect(eventRegistryApi.usages).toHaveBeenCalledWith('paymentReceived')
    expect(wrapper.get('[data-row="usage"]').text()).toContain('orders v3 · pay, refund')
    expect(wrapper.get('[data-field="waiting"]').text()).toBe('2 công việc đang chờ')
    expect(wrapper.find('[data-warning="in-use"]').exists()).toBe(false)

    await wrapper.get('[data-field="event-name"]').setValue('Tên mới')

    expect(wrapper.get('[data-warning="in-use"]').text()).toContain('Chỉ thêm trường mới')
  })

  it('publishes a changed event from its row and keeps an unchanged one disabled', async () => {
    vi.mocked(eventRegistryApi.events).mockResolvedValue([
      structuredClone(published),
      { ...structuredClone(published), key: 'refundIssued', name: 'Đã hoàn tiền', changed: true },
    ])
    vi.mocked(eventRegistryApi.publishEvent).mockResolvedValue({ ...published, publishedVersion: 2 })
    const wrapper = mountPanel()
    await flushPromises()

    expect(
      wrapper.get('[data-action="publish-event-row"][data-event="paymentReceived"]').attributes('disabled'),
    ).toBeDefined()
    await wrapper.get('[data-action="publish-event-row"][data-event="refundIssued"]').trigger('click')
    await flushPromises()
    expect(eventRegistryApi.publishEvent).toHaveBeenCalledWith('refundIssued', 2)
  })
})
