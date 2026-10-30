import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ServiceListPanel from './ServiceListPanel.vue'
import type { ServiceView } from './api'

const services: ServiceView[] = [
  {
    serviceKey: 'crm',
    name: 'CRM',
    operations: [
      {
        operationKey: 'createLead',
        method: 'POST',
        urlTemplate: 'https://crm.example',
        authType: 'NONE',
        secretPresent: false,
        timeoutMillis: 5000,
        errorStatusCodes: [],
        bpmnErrorCode: 'HTTP_ERROR',
        publishedUrl: 'https://crm.example',
        publishedMethod: 'POST',
        publishedBpmnErrorCode: 'HTTP_ERROR',
        inputParameters: [],
        outputParameters: [],
        publishedInputParameters: [],
        publishedOutputParameters: [],
      },
    ],
  },
  {
    serviceKey: 'billing',
    name: 'Billing',
    operations: [
      {
        operationKey: 'charge',
        method: 'POST',
        urlTemplate: 'https://billing.example',
        authType: 'NONE',
        secretPresent: false,
        timeoutMillis: 5000,
        errorStatusCodes: [],
        bpmnErrorCode: 'HTTP_ERROR',
      },
    ],
  },
]

describe('ServiceListPanel', () => {
  it('filters without case and marks a service that still needs publish', () => {
    const wrapper = mount(ServiceListPanel, {
      props: { services, selectedKey: 'crm', query: 'bill' },
    })

    expect(wrapper.text()).toContain('Billing')
    expect(wrapper.text()).toContain('Cần phát hành')
    expect(wrapper.text()).not.toContain('CRM')
  })

  it('says when nothing matches and emits the selected service', async () => {
    const wrapper = mount(ServiceListPanel, {
      props: { services, selectedKey: 'crm', query: 'zzz' },
    })

    expect(wrapper.text()).toContain('Không có dịch vụ khớp')
    await wrapper.setProps({ query: '' })
    await wrapper.get('[data-service="billing"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['billing'])
  })
})
