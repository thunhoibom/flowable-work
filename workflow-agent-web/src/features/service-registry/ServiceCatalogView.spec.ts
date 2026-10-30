import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import ServiceCatalogView from './ServiceCatalogView.vue'
import { confirmAction } from '@/utils/confirmation'
import { serviceCatalogApi } from './api'

vi.mock('./api', () => ({
  serviceCatalogApi: {
    list: vi.fn(),
    create: vi.fn(),
    saveOperation: vi.fn(),
    publish: vi.fn(),
    deleteOperation: vi.fn(),
    deleteService: vi.fn(),
  },
}))

vi.mock('@/utils/confirmation', () => ({
  confirmAction: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

const inputStub = {
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const slot = { template: '<div><slot /></div>' }

const publishedOperation = {
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
  publishedUrl: 'https://crm.example/orders/${orderId}',
  publishedMethod: 'POST',
  publishedBpmnErrorCode: 'HTTP_ERROR',
  publishedInputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
  publishedOutputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
}

const unpublishedOperation = {
  operationKey: 'findLead',
  method: 'GET',
  urlTemplate: 'https://crm.example/leads/${leadId}',
  authType: 'API_KEY',
  apiKeyHeader: 'X-Api-Key',
  secretPresent: true,
  timeoutMillis: 5000,
  errorStatusCodes: ['409', '5XX'],
  bpmnErrorCode: 'HTTP_ERROR',
  inputParameters: [],
  outputParameters: [],
}

function mountView() {
  return mount(ServiceCatalogView, {
    global: {
      stubs: {
        'el-form': slot,
        'el-form-item': slot,
        'el-dialog': slot,
        'el-select': slot,
        'el-option': slot,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-input': inputStub,
        'el-input-number': inputStub,
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          template: '<input type="checkbox" :disabled="disabled" :checked="modelValue" />',
        },
      },
    },
  })
}

describe('ServiceCatalogView', () => {
  beforeEach(() => {
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([
      { serviceKey: 'crm', name: 'CRM', operations: [publishedOperation, unpublishedOperation] },
    ])
    vi.mocked(serviceCatalogApi.create).mockResolvedValue()
    vi.mocked(serviceCatalogApi.saveOperation).mockResolvedValue()
    vi.mocked(serviceCatalogApi.publish).mockResolvedValue()
    vi.mocked(serviceCatalogApi.deleteOperation).mockResolvedValue()
    vi.mocked(serviceCatalogApi.deleteService).mockResolvedValue()
    vi.mocked(confirmAction).mockReset()
    vi.mocked(confirmAction).mockResolvedValue(false)
    vi.mocked(ElMessage.warning).mockReset()
  })

  it('loads a draft operation and locks a path parameter', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('button[data-operation="createLead"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).disabled).toBe(true)
    expect((wrapper.get('[data-field="operation-key"]').element as HTMLInputElement).disabled).toBe(
      true,
    )
  })

  it('shows the parameter editors under the operation form', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).not.toContain('Đầu vào')
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Đầu vào')
    expect(wrapper.text()).toContain('Thêm đầu vào')
    expect(wrapper.text()).toContain('Đầu ra')
    expect(wrapper.text()).toContain('Thêm đầu ra')
  })

  it('ignores a service that has no key', async () => {
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([
      { serviceKey: '', name: '', operations: [] },
      { serviceKey: 'crm', name: 'CRM', operations: [publishedOperation] },
    ])
    vi.mocked(confirmAction).mockResolvedValue(true)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-action="delete-service"]').trigger('click')
    await flushPromises()
    expect(serviceCatalogApi.deleteService).toHaveBeenCalledWith('crm')
  })

  it('shows an empty catalog after loading', async () => {
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('Chưa có dịch vụ')
  })

  it('keeps the selected service when the search matches nothing', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('input[aria-label="Tìm dịch vụ"]').setValue('zzz')
    expect(wrapper.text()).toContain('Không có dịch vụ khớp')
    expect(wrapper.text()).toContain('CRM')
  })

  it('does not save when the url or api key header is missing', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-field="operation-key"]').setValue('createLead')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(serviceCatalogApi.saveOperation).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('Thiếu mẫu địa chỉ')

    vi.mocked(confirmAction).mockResolvedValueOnce(true)
    await wrapper.get('button[data-operation="findLead"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-field="api-key-header"]').setValue('')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(serviceCatalogApi.saveOperation).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('Thiếu header API key')
  })

  it('publishes a blank new operation and blocks a dirty form', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(serviceCatalogApi.publish).toHaveBeenCalledWith('crm')

    await wrapper.get('button[data-operation="createLead"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-field="url"]').setValue('https://changed.example')
    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(serviceCatalogApi.publish).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('Lưu thao tác trước khi phát hành')

    await wrapper.get('button[data-operation="findLead"]').trigger('click')
    await flushPromises()
    expect(confirmAction).toHaveBeenCalled()
    expect((wrapper.get('[data-field="url"]').element as HTMLInputElement).value).toBe(
      'https://changed.example',
    )
  })

  it('deletes an operation only after confirmation and drops it from the draft list', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-operation-key="findLead"]').trigger('click')
    expect(serviceCatalogApi.deleteOperation).not.toHaveBeenCalled()

    vi.mocked(confirmAction).mockResolvedValueOnce(true)
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([
      { serviceKey: 'crm', name: 'CRM', operations: [publishedOperation] },
    ])
    await wrapper.get('[data-operation-key="findLead"]').trigger('click')
    await flushPromises()

    expect(serviceCatalogApi.deleteOperation).toHaveBeenCalledWith('crm', 'findLead')
    expect(wrapper.text()).not.toContain('findLead')
  })
})
