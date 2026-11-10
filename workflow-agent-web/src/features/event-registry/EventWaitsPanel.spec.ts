import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PageResult } from '@/types/api'
import { tableStubs } from '@/testing/tableStubs'
import EventWaitsPanel from './EventWaitsPanel.vue'
import { eventRegistryApi, type EventModelView, type EventWaitView } from './api'

vi.mock('./api', () => ({
  eventRegistryApi: {
    events: vi.fn<() => Promise<EventModelView[]>>(),
    waits:
      vi.fn<(eventKey: string, page: number, size: number) => Promise<PageResult<EventWaitView>>>(),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn<(message: string) => void>() },
}))

function event(key: string, publishedVersion: number | null): EventModelView {
  return {
    key,
    name: key,
    fields: [],
    revision: 1,
    updatedBy: 'designer',
    updatedAt: '2026-11-10T00:00:00Z',
    publishedVersion,
    changed: false,
  }
}

function wait(index: number): EventWaitView {
  return {
    processInstanceId: `pi-${index}`,
    processDefinitionKey: 'orders',
    processDefinitionName: 'Đơn hàng',
    businessKey: `SO-${index}`,
    activityId: 'pay',
    activityName: 'Chờ thanh toán',
    executionId: `ex-${index}`,
    since: '2026-11-10T01:00:00Z',
  }
}

const paginationStub = {
  props: ['total', 'currentPage', 'pageSize'],
  emits: ['change'],
  template:
    '<button type="button" data-action="next-page" @click="$emit(\'change\', currentPage + 1, pageSize)">{{ total }}</button>',
}

function mountPanel() {
  return mount(EventWaitsPanel, {
    global: {
      directives: { loading: {} },
      stubs: {
        ...tableStubs,
        'el-button': { template: '<button type="button"><slot /></button>' },
        TablePagination: paginationStub,
        ListEmptyState: { props: ['title'], template: '<p data-empty>{{ title }}</p>' },
        RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
      },
    },
  })
}

describe('EventWaitsPanel', () => {
  beforeEach(() => {
    vi.mocked(eventRegistryApi.events).mockResolvedValue([
      event('draftOnly', null),
      event('paymentReceived', 1),
    ])
  })

  it('lists the waits of the first published event and pages', async () => {
    vi.mocked(eventRegistryApi.waits)
      .mockResolvedValueOnce({ records: [wait(1), wait(2)], total: 3, pageNum: 1, pageSize: 20 })
      .mockResolvedValueOnce({ records: [wait(3)], total: 3, pageNum: 2, pageSize: 20 })
    const wrapper = mountPanel()
    await flushPromises()

    expect(eventRegistryApi.waits).toHaveBeenCalledWith('paymentReceived', 1, 20)
    expect(wrapper.findAll('option').map((option) => option.text())).toEqual([
      'paymentReceived (paymentReceived)',
    ])
    expect(wrapper.findAll('[data-row="wait"]')).toHaveLength(2)
    const link = wrapper.get('[data-row="wait"] a')
    expect(link.text()).toBe('SO-1')
    expect(JSON.parse(link.attributes('data-to')!)).toEqual({
      name: 'process-instance-detail',
      params: { id: 'pi-1' },
    })

    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()

    expect(eventRegistryApi.waits).toHaveBeenLastCalledWith('paymentReceived', 2, 20)
    expect(wrapper.findAll('[data-row="wait"]')).toHaveLength(1)
  })

  it('shows the empty state when nothing waits', async () => {
    vi.mocked(eventRegistryApi.waits).mockResolvedValue({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-empty]').text()).toBe('Không có công việc nào đang chờ sự kiện này')
  })
})
