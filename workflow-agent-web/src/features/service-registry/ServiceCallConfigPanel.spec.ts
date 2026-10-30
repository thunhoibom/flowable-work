import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ServiceCallConfigPanel from './ServiceCallConfigPanel.vue'

const slot = { template: '<div><slot /></div>' }

describe('ServiceCallConfigPanel', () => {
  it('shows the published contract and the current mapping rows', () => {
    const wrapper = mount(ServiceCallConfigPanel, {
      props: {
        serviceKey: 'crm',
        operationKey: 'createLead',
        services: [
          {
            serviceKey: 'crm',
            name: 'CRM',
            operations: [
              {
                operationKey: 'createLead',
                method: 'POST',
                urlTemplate: 'https://draft.example',
                authType: 'NONE',
                secretPresent: false,
                timeoutMillis: 1000,
                errorStatusCodes: [],
                bpmnErrorCode: 'DRAFT_ERROR',
                publishedUrl: 'https://crm.example/orders/${orderId}',
                publishedMethod: 'POST',
                publishedBpmnErrorCode: 'HTTP_ERROR',
                inputParameters: [],
                outputParameters: [],
                publishedInputParameters: [],
                publishedOutputParameters: [],
              },
            ],
          },
        ],
        inputRows: [
          { name: 'orderId', type: 'STRING', required: true, location: 'PATH', expression: '${orderId}' },
        ],
        outputRows: [{ name: 'id', type: 'LONG', jsonField: 'id', variable: 'leadId' }],
      },
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
        },
      },
    })

    expect(wrapper.text()).toContain('POST')
    expect(wrapper.text()).toContain('https://crm.example/orders/${orderId}')
    expect(wrapper.text()).toContain('HTTP_ERROR')
    expect(wrapper.text()).not.toContain('DRAFT_ERROR')
    expect(wrapper.findAll('input').map((input) => input.element.value)).toEqual(['${orderId}', 'leadId'])
  })

  it('asks to store the mapping when an expression or variable changes', async () => {
    const wrapper = mount(ServiceCallConfigPanel, {
      props: {
        serviceKey: 'crm',
        operationKey: 'createLead',
        services: [],
        inputRows: [
          { name: 'orderId', type: 'STRING', required: true, location: 'PATH', expression: '${orderId}' },
        ],
        outputRows: [{ name: 'id', type: 'LONG', jsonField: 'id', variable: 'id' }],
      },
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-input': {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template:
              '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          },
        },
      },
    })

    const [expression, variable] = wrapper.findAll('input')
    if (!expression || !variable) throw new Error('missing mapping inputs')
    await expression.setValue('${paymentRequestId}')
    await variable.setValue('paymentRequest')

    const inputs = wrapper.emitted('update:inputRows')
    const outputs = wrapper.emitted('update:outputRows')
    expect(wrapper.emitted('apply')).toHaveLength(2)
    expect(inputs?.[0]?.[0]).toEqual([
      expect.objectContaining({ name: 'orderId', expression: '${paymentRequestId}' }),
    ])
    expect(outputs?.[0]?.[0]).toEqual([
      expect.objectContaining({ name: 'id', variable: 'paymentRequest' }),
    ])
  })
})
