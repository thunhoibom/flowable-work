import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ServiceCatalogView from './ServiceCatalogView.vue'

vi.mock('./api', () => ({
  serviceCatalogApi: {
    list: vi.fn().mockResolvedValue([
      {
        serviceKey: 'crm',
        name: 'CRM',
        operations: [
          {
            operationKey: 'createLead',
            method: 'POST',
            urlTemplate: 'https://crm.example/orders/${orderId}',
            authType: 'NONE',
            secretPresent: false,
            bodyTemplate: '{"sku":1}',
            timeoutMillis: 1000,
            errorStatusCodes: ['409'],
            bpmnErrorCode: 'HTTP_ERROR',
            inputParameters: [
              { name: 'orderId', type: 'STRING', required: true, location: 'PATH' },
            ],
            outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
            publishedInputParameters: [],
            publishedOutputParameters: [],
          },
        ],
      },
    ]),
    create: vi.fn(),
    saveOperation: vi.fn(),
    publish: vi.fn(),
  },
}))

const slot = { template: '<div><slot /></div>' }

describe('ServiceCatalogView', () => {
  it('loads a draft operation and locks a path parameter', async () => {
    const wrapper = mount(ServiceCatalogView, {
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-button': { template: '<button type="button"><slot /></button>' },
          'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-input-number': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-checkbox': {
            props: ['modelValue', 'disabled'],
            template: '<input type="checkbox" :disabled="disabled" />',
          },
        },
      },
    })
    await flushPromises()
    await wrapper.get('button[data-operation="createLead"]').trigger('click')

    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
    const checkbox = wrapper.get('input[type="checkbox"]')
    expect((checkbox.element as HTMLInputElement).disabled).toBe(true)
  })

  it('shows the parameter editors under the operation form', async () => {
    const wrapper = mount(ServiceCatalogView, {
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-button': { template: '<button type="button"><slot /></button>' },
          'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-input-number': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-checkbox': {
            props: ['modelValue', 'disabled'],
            template: '<input type="checkbox" :disabled="disabled" />',
          },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Đầu vào')
    expect(wrapper.text()).toContain('Thêm đầu vào')
    expect(wrapper.text()).toContain('Đầu ra')
    expect(wrapper.text()).toContain('Thêm đầu ra')
  })
})
