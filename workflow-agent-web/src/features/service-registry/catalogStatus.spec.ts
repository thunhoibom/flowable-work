import { describe, expect, it } from 'vitest'
import { operationPublishStatus, serviceNeedsPublish } from './catalogStatus'
import type { CatalogOperation } from './api'

function operation(overrides: Partial<CatalogOperation> = {}): CatalogOperation {
  return {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'NONE',
    secretPresent: false,
    bodyTemplate: '{"sku":1}',
    timeoutMillis: 5000,
    errorStatusCodes: ['409', '5XX'],
    bpmnErrorCode: 'HTTP_ERROR',
    publishedUrl: 'https://crm.example/orders/${orderId}',
    publishedMethod: 'POST',
    publishedBpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
    outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    publishedInputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
    publishedOutputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    ...overrides,
  }
}

describe('operationPublishStatus', () => {
  it('labels a matching snapshot as published', () => {
    expect(operationPublishStatus(operation())).toBe('published')
  })

  it('labels a missing published url as unpublished', () => {
    expect(operationPublishStatus(operation({ publishedUrl: undefined }))).toBe('unpublished')
    expect(operationPublishStatus(operation({ publishedUrl: '' }))).toBe('unpublished')
  })

  it('treats reordered parameters as the same snapshot', () => {
    expect(
      operationPublishStatus(
        operation({
          inputParameters: [
            { name: 'b', type: 'STRING', required: false, location: 'QUERY' },
            { name: 'a', type: 'STRING', required: true, location: 'PATH' },
          ],
          publishedInputParameters: [
            { name: 'a', type: 'STRING', required: true, location: 'PATH' },
            { name: 'b', type: 'STRING', required: false, location: 'QUERY' },
          ],
          outputParameters: [
            { name: 'second', type: 'STRING', jsonField: 'second' },
            { name: 'id', type: 'LONG', jsonField: 'id' },
          ],
          publishedOutputParameters: [
            { name: 'id', type: 'LONG', jsonField: 'id' },
            { name: 'second', type: 'STRING', jsonField: 'second' },
          ],
        }),
      ),
    ).toBe('published')
  })

  it('labels a different method, url, error code, or parameter as changed', () => {
    expect(operationPublishStatus(operation({ method: 'PUT' }))).toBe('changed')
    expect(operationPublishStatus(operation({ urlTemplate: 'https://other.example' }))).toBe('changed')
    expect(operationPublishStatus(operation({ bpmnErrorCode: 'CRM_ERROR' }))).toBe('changed')
    expect(
      operationPublishStatus(
        operation({
          inputParameters: [{ name: 'orderId', type: 'LONG', required: true, location: 'PATH' }],
        }),
      ),
    ).toBe('changed')
  })

  it('ignores auth, secret, body, timeout, and http error codes', () => {
    expect(
      operationPublishStatus(
        operation({
          authType: 'BEARER',
          secretPresent: true,
          bodyTemplate: '{"sku":2}',
          timeoutMillis: 1000,
          errorStatusCodes: ['500'],
        }),
      ),
    ).toBe('published')
  })

  it('treats a missing published method or parameter list as changed only when the draft has a value', () => {
    expect(operationPublishStatus(operation({ publishedMethod: undefined, method: 'POST' }))).toBe(
      'changed',
    )
    expect(
      operationPublishStatus(
        operation({
          publishedMethod: undefined,
          method: '',
          publishedInputParameters: undefined,
          inputParameters: [],
          publishedOutputParameters: undefined,
          outputParameters: [],
        }),
      ),
    ).toBe('published')
    expect(
      operationPublishStatus(
        operation({
          publishedInputParameters: undefined,
          inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
        }),
      ),
    ).toBe('changed')
  })
})

describe('serviceNeedsPublish', () => {
  it('is true when any operation is not published', () => {
    expect(serviceNeedsPublish([operation(), operation({ publishedUrl: undefined })])).toBe(true)
    expect(serviceNeedsPublish([operation()])).toBe(false)
    expect(serviceNeedsPublish([])).toBe(false)
  })
})
