import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { tableStubs } from '@/testing/tableStubs'
import OperationTable from './OperationTable.vue'
import type { CatalogOperation } from './api'

const operations: CatalogOperation[] = [
  {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'NONE',
    secretPresent: true,
    timeoutMillis: 5000,
    errorStatusCodes: [],
    bpmnErrorCode: 'HTTP_ERROR',
    publishedUrl: 'https://crm.example/orders/${orderId}',
    publishedMethod: 'POST',
    publishedBpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [],
    outputParameters: [],
    publishedInputParameters: [],
    publishedOutputParameters: [],
  },
]

const global = {
  stubs: { ...tableStubs, 'el-button': { template: '<button type="button"><slot /></button>' } },
  directives: { loading: {} },
}

describe('OperationTable', () => {
  it('shows the operation number and opens edit from the action, not the row', async () => {
    const wrapper = mount(OperationTable, { props: { operations }, global })

    expect(wrapper.find('[data-column="STT"]').text()).toBe('1')
    expect(wrapper.find('[data-column="Hành động"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('POST')
    expect(wrapper.text()).toContain('JSON')
    expect(wrapper.text()).toContain('Đã phát hành')
    expect(wrapper.text()).toContain('Đã lưu khóa')

    await wrapper.get('[data-row]').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()

    await wrapper.get('[data-action="edit-operation"]').trigger('click')
    await wrapper.get('[data-action="delete-operation"]').trigger('click')
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['createLead'])
    expect(wrapper.emitted('remove')?.[0]).toEqual(['createLead'])
    expect(wrapper.emitted('add')).toHaveLength(1)
  })

  it('shows an empty operation list', () => {
    const wrapper = mount(OperationTable, { props: { operations: [] }, global })
    expect(wrapper.text()).toContain('Chưa có thao tác')
  })
})
