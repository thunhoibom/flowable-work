import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OperationEditor from './OperationEditor.vue'
import { blankOperationDraft, draftFromOperation } from './operationDraft'
import type { CatalogOperation } from './api'

const inputStub = {
  props: ['modelValue', 'disabled', 'type', 'placeholder'],
  template:
    '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

const slot = { template: '<div><slot /></div>' }

function mountEditor(
  draft = blankOperationDraft(),
  props: Record<string, unknown> = {},
) {
  return mount(OperationEditor, {
    props: {
      modelValue: draft,
      operationKeyLocked: false,
      secretPresent: false,
      saving: false,
      ...props,
    },
    global: {
      stubs: {
        'el-form': slot,
        'el-form-item': slot,
        'el-input': inputStub,
        'el-input-number': inputStub,
        'el-select': slot,
        'el-option': slot,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          template: '<input type="checkbox" :disabled="disabled" :checked="modelValue" />',
        },
      },
    },
  })
}

describe('OperationEditor', () => {
  it('locks an existing operation key and a path parameter', () => {
    const operation: CatalogOperation = {
      operationKey: 'createLead',
      method: 'POST',
      urlTemplate: 'https://crm.example/orders/${orderId}',
      authType: 'NONE',
      secretPresent: false,
      bodyTemplate: '{"sku":1}',
      timeoutMillis: 1000,
      errorStatusCodes: ['409'],
      bpmnErrorCode: 'HTTP_ERROR',
      inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
      outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    }
    const wrapper = mountEditor(draftFromOperation(operation), { operationKeyLocked: true })

    expect(wrapper.text()).toContain('Yêu cầu')
    expect(wrapper.text()).toContain('Xác thực')
    expect(wrapper.text()).toContain('Lỗi')
    expect(wrapper.text()).toContain('Đầu vào')
    expect(wrapper.text()).toContain('Đầu ra')
    expect((wrapper.get('[data-field="operation-key"]').element as HTMLInputElement).disabled).toBe(
      true,
    )
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
  })

  it('shows the saved-secret hint and emits save', async () => {
    const wrapper = mountEditor(blankOperationDraft(), { secretPresent: true })
    expect(wrapper.text()).toContain('Đã lưu khóa. Để trống để giữ khóa này.')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })
})
