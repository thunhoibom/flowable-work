import { describe, expect, it } from 'vitest'
import type { CatalogOperation } from './api'
import {
  blankOperationDraft,
  draftFromOperation,
  isOperationDraftDirty,
  toSaveCommand,
  validateOperationDraft,
} from './operationDraft'

function operation(): CatalogOperation {
  return {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'API_KEY',
    apiKeyHeader: 'X-Api-Key',
    secretPresent: true,
    bodyTemplate: '{"sku":1}',
    timeoutMillis: 1000,
    errorStatusCodes: ['409', '5XX'],
    bpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [{ name: 'orderId', type: 'STRING', required: false, location: 'PATH' }],
    outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
  }
}

describe('operation draft', () => {
  it('treats two blank drafts as clean and an edited url as dirty', () => {
    const baseline = blankOperationDraft()
    expect(isOperationDraftDirty(blankOperationDraft(), baseline)).toBe(false)
    expect(isOperationDraftDirty({ ...baseline, url: 'https://crm.example' }, baseline)).toBe(true)
  })

  it('loads an operation without copying the secret and locks path parameters', () => {
    const draft = draftFromOperation(operation())
    expect(draft.secret).toBe('')
    expect(draft.errorStatusCodes).toBe('409,5XX')
    expect(draft.inputs[0]?.required).toBe(true)
    expect(isOperationDraftDirty(draft, draftFromOperation(operation()))).toBe(false)
  })

  it('names the first missing field', () => {
    const draft = blankOperationDraft()
    expect(validateOperationDraft(draft)).toBe('Thiếu mã thao tác')
    expect(validateOperationDraft({ ...draft, operationKey: 'createLead' })).toBe('Thiếu mẫu địa chỉ')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        timeoutMillis: 0,
      }),
    ).toBe('Thời gian chờ phải từ 1 mili giây')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        authType: 'API_KEY',
      }),
    ).toBe('Thiếu header API key')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        inputs: [{ name: ' ', type: 'STRING', required: false, location: 'QUERY' }],
      }),
    ).toBe('Thiếu tên tham số đầu vào')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        outputs: [{ name: ' ', type: 'STRING', jsonField: 'id' }],
      }),
    ).toBe('Thiếu tên tham số đầu ra')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        outputs: [{ name: 'id', type: 'STRING', jsonField: ' ' }],
      }),
    ).toBe('Thiếu trường JSON đầu ra')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: ' createLead ',
        url: ' https://crm.example ',
      }),
    ).toBeNull()
  })

  it('builds the existing save payload', () => {
    const draft = draftFromOperation(operation())
    draft.secret = ''
    draft.bpmnErrorCode = ' '
    draft.apiKeyHeader = ' X-Api-Key '
    expect(toSaveCommand(draft)).toEqual({
      method: 'POST',
      url: 'https://crm.example/orders/${orderId}',
      authType: 'API_KEY',
      apiKeyHeader: 'X-Api-Key',
      secret: undefined,
      bodyTemplate: '{"sku":1}',
      timeoutMillis: 1000,
      errorStatusCodes: ['409', '5XX'],
      bpmnErrorCode: 'HTTP_ERROR',
      inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
      outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    })
  })
})
