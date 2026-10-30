import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
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

describe('OperationTable', () => {
  it('shows the operation, its publish label, and emits select or add', async () => {
    const wrapper = mount(OperationTable, {
      props: { operations, selectedOperationKey: '' },
    })

    expect(wrapper.text()).toContain('POST')
    expect(wrapper.text()).toContain('Đã phát hành')
    expect(wrapper.text()).toContain('Đã lưu khóa')
    await wrapper.get('[data-operation="createLead"]').trigger('click')
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['createLead'])
    expect(wrapper.emitted('add')).toHaveLength(1)
  })

  it('shows an empty operation list', () => {
    const wrapper = mount(OperationTable, {
      props: { operations: [], selectedOperationKey: '' },
    })
    expect(wrapper.text()).toContain('Chưa có thao tác')
  })
})
