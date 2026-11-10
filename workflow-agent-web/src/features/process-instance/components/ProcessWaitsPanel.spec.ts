import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { describe, expect, it, vi } from 'vitest'
import { processInstanceApi } from '../api'
import type { ProcessWait } from '../types'
import ProcessWaitsPanel from './ProcessWaitsPanel.vue'

vi.mock('../api', () => ({
  processInstanceApi: {
    waits: vi.fn<(processInstanceId: string) => Promise<ProcessWait[]>>(),
    triggerReceiveTask: vi.fn<() => Promise<void>>(),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn<(message: string) => void>() },
}))

const MANUAL: ProcessWait = {
  kind: 'MANUAL',
  activityId: 'confirm',
  activityName: 'Chờ xác nhận',
  executionId: 'e1',
  since: '2026-11-10T01:00:00Z',
}

function eventWait(changed: boolean | null): ProcessWait {
  return {
    kind: 'EVENT',
    activityId: 'pay',
    activityName: 'Chờ thanh toán',
    executionId: 'e2',
    since: '2026-11-10T02:00:00Z',
    elementType: 'receiveTask',
    eventKey: 'paymentReceived',
    correlations: [
      { name: 'orderId', expression: '${orderId}', currentValue: 'SO-1', evaluated: true },
      { name: 'region', expression: '${regions.first()}', currentValue: null, evaluated: false },
    ],
    correlationChanged: changed,
    dueDate: '2026-11-11T02:00:00Z',
  }
}

async function mountPanel(waits: ProcessWait[], canOperate = true) {
  vi.mocked(processInstanceApi.waits).mockResolvedValue(waits)
  const wrapper = mount(ProcessWaitsPanel, {
    props: { processInstanceId: 'p1', tenantCode: 't1', canOperate },
    global: {
      plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]],
      directives: { loading: {} },
      stubs: {
        'el-button': {
          template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
        },
        'el-tag': { template: '<span class="tag"><slot /></span>' },
        'el-alert': { props: ['title'], template: '<p class="alert">{{ title }}</p>' },
        'el-dialog': true,
        'el-form': true,
        'el-form-item': true,
        'el-input': true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('ProcessWaitsPanel', () => {
  it('shows an event wait with its values and no continue button', async () => {
    const wrapper = await mountPanel([eventWait(false)])

    const item = wrapper.find('.wait-event')
    expect(item.text()).toContain('Chờ sự kiện')
    expect(item.text()).toContain('paymentReceived')
    expect(item.text()).toContain('Bước chờ')
    expect(item.text()).toContain('Hạn chờ')
    expect(item.find('.wait-correlations').text()).toContain('SO-1')
    expect(item.find('.not-evaluated').text()).toBe('không tính lại')
    expect(item.find('.wait-continue').exists()).toBe(false)
    expect(item.find('.alert').exists()).toBe(false)
  })

  it('warns only when the correlation changed', async () => {
    const changed = await mountPanel([eventWait(true)])
    expect(changed.find('.alert').text()).toContain('Biến đã đổi sau khi bắt đầu chờ')

    const unknown = await mountPanel([eventWait(null)])
    expect(unknown.find('.alert').exists()).toBe(false)
  })

  it('keeps the continue button on manual waits for operators', async () => {
    const operator = await mountPanel([MANUAL, eventWait(false)])
    expect(operator.findAll('.wait-continue')).toHaveLength(1)
    expect(operator.find('.wait-manual .wait-continue').text()).toBe('Tiếp tục')

    const viewer = await mountPanel([MANUAL], false)
    expect(viewer.find('.wait-continue').exists()).toBe(false)
  })
})
