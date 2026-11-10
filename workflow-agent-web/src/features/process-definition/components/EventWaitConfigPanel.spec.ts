import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { BpmnElement, BpmnModelerInstance, ModdleElement } from '@/bpmn/modeler-types'
import type { EventWaitEvent } from '@/bpmn/eventWaitModel'
import EventWaitConfigPanel from './EventWaitConfigPanel.vue'

const PAYMENT: EventWaitEvent = {
  key: 'paymentReceived',
  name: 'Đã nhận thanh toán',
  description: 'ERP báo đã thu tiền',
  fields: [],
  publishedFields: [
    { name: 'orderId', correlation: true, type: 'string' },
    { name: 'region', correlation: true, type: 'string' },
    { name: 'amount', correlation: false, type: 'double' },
  ],
}

const LISTENER: ModdleElement = {
  $type: 'flowable:ExecutionListener',
  event: 'start',
  delegateExpression: '${audit}',
}

function setup(values: ModdleElement[]) {
  const businessObject: ModdleElement = {
    $type: 'bpmn:IntermediateCatchEvent',
    asyncLeave: true,
    extensionElements: { $type: 'bpmn:ExtensionElements', values },
  }
  const element: BpmnElement = { id: 'wait', businessObject }
  const updateModdleProperties =
    vi.fn<
      (element: BpmnElement, object: ModdleElement, properties: Record<string, unknown>) => void
    >()
  const updateProperties =
    vi.fn<(element: BpmnElement, properties: Record<string, unknown>) => void>()
  const services: Record<string, unknown> = {
    modeling: { updateModdleProperties, updateProperties },
    moddle: {
      create: (type: string, properties?: Record<string, unknown>) => ({
        $type: type,
        ...properties,
      }),
    },
  }
  const modeler = { get: (name: string) => services[name] } as unknown as BpmnModelerInstance
  const wrapper = mount(EventWaitConfigPanel, {
    props: { element, modeler, events: [PAYMENT] },
    global: {
      stubs: {
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div><slot /></div>' },
        'el-select': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
        },
        'el-option': {
          props: ['value', 'label'],
          template: '<option :value="value">{{ label }}</option>',
        },
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-checkbox': { template: '<label><slot /></label>' },
        'el-alert': {
          props: ['title', 'type'],
          template: '<p :class="`alert-${type}`">{{ title }}</p>',
        },
      },
    },
  })
  const lastValues = () => {
    const properties = updateModdleProperties.mock.lastCall?.[2]
    return ((properties?.extensionElements as ModdleElement | undefined)?.values ??
      []) as ModdleElement[]
  }
  return { wrapper, updateModdleProperties, lastValues }
}

describe('EventWaitConfigPanel', () => {
  it('creates one correlation row per correlation field when an event is picked', async () => {
    const { wrapper, lastValues } = setup([{ $type: 'flowable:EventType', body: '' }])

    await wrapper.find('select.event-select').setValue('paymentReceived')

    expect(
      wrapper
        .findAll('.correlation-value')
        .map((input) => (input.element as HTMLInputElement).value),
    ).toEqual(['${orderId}', '${region}'])
    expect(
      lastValues().filter((value) => value.$type === 'flowable:EventCorrelationParameter'),
    ).toEqual([
      expect.objectContaining({ name: 'orderId', value: '${orderId}' }),
      expect.objectContaining({ name: 'region', value: '${region}' }),
    ])
    expect(wrapper.text()).toContain('ERP báo đã thu tiền')
  })

  it('warns when a correlation row is removed (R-B4) and can add it back', async () => {
    const { wrapper } = setup([
      { $type: 'flowable:EventType', body: 'paymentReceived' },
      { $type: 'flowable:EventCorrelationParameter', name: 'orderId', value: '${orderId}' },
      { $type: 'flowable:EventCorrelationParameter', name: 'region', value: '${region}' },
    ])
    expect(wrapper.findAll('.alert-warning')).toHaveLength(0)

    await wrapper.findAll('.remove-correlation')[1]!.trigger('click')

    expect(wrapper.find('.alert-warning').text()).toContain('Trường tương quan region')
    await wrapper.find('.add-correlation').trigger('click')
    expect(wrapper.findAll('.alert-warning')).toHaveLength(0)
  })

  it('shows a problem for an internal target and keeps the execution listener', async () => {
    const { wrapper, lastValues } = setup([
      { $type: 'flowable:EventType', body: 'paymentReceived' },
      { $type: 'flowable:EventCorrelationParameter', name: 'orderId', value: '${orderId}' },
      { $type: 'flowable:EventCorrelationParameter', name: 'region', value: '${region}' },
      { $type: 'flowable:EventOutParameter', source: 'amount', target: 'paid' },
      LISTENER,
    ])

    const target = wrapper.find('.output-target')
    await target.setValue('tenantId')
    await target.trigger('blur')

    expect(wrapper.find('.alert-error').text()).toBe('Biến tenantId do hệ thống quản lý')
    expect(lastValues().map((value) => value.$type)).toEqual([
      'flowable:EventType',
      'flowable:EventCorrelationParameter',
      'flowable:EventCorrelationParameter',
      'flowable:EventOutParameter',
      'flowable:ExecutionListener',
    ])
    expect(lastValues()[4]).toStrictEqual(LISTENER)
  })
})
