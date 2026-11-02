import type { CatalogInputParameter, CatalogOperation, CatalogOutputParameter, CatalogStep } from './api'

export type OperationPublishStatus = 'unpublished' | 'changed' | 'published'

export const operationStatusLabel: Record<OperationPublishStatus, string> = {
  unpublished: 'Chưa phát hành',
  changed: 'Có thay đổi',
  published: 'Đã phát hành',
}

export function operationPublishStatus(operation: CatalogOperation): OperationPublishStatus {
  if (!operation.publishedUrl) return 'unpublished'
  if (
    !sameText(operation.method, operation.publishedMethod) ||
    operation.urlTemplate !== operation.publishedUrl ||
    !sameText(operation.bpmnErrorCode, operation.publishedBpmnErrorCode) ||
    !sameInputs(operation.inputParameters, operation.publishedInputParameters) ||
    !sameOutputs(operation.outputParameters, operation.publishedOutputParameters) ||
    !sameSteps(operation.steps, operation.publishedSteps)
  ) {
    return 'changed'
  }
  return 'published'
}

export function serviceNeedsPublish(operations: CatalogOperation[]): boolean {
  return operations.some((operation) => operationPublishStatus(operation) !== 'published')
}

function sameText(draft: string | null | undefined, published: string | null | undefined) {
  return (draft ?? '') === (published ?? '')
}

function sameInputs(
  draft: CatalogInputParameter[] | undefined,
  published: CatalogInputParameter[] | undefined,
) {
  const left = draft ?? []
  if (published == null) return left.length === 0
  return sameMembers(left, published, (row) =>
    [row.name, row.type, String(row.required), row.location].join('\u0000'),
  )
}

function sameOutputs(
  draft: CatalogOutputParameter[] | undefined,
  published: CatalogOutputParameter[] | undefined,
) {
  const left = draft ?? []
  if (published == null) return left.length === 0
  return sameMembers(left, published, (row) =>
    [row.name, row.type, row.source || 'JSON_FIELD', row.jsonField || '', row.headerName || ''].join('\u0000'),
  )
}

function sameSteps(draft: CatalogStep | undefined, published: CatalogStep | undefined) {
  return stepSignature(draft) === stepSignature(published)
}

function stepSignature(step: CatalogStep | undefined) {
  return [
    step?.pagination || 'NONE',
    step?.nextField || '',
    step?.nextTokenParameter || '',
    String(step?.maxPages || 10),
    step?.callStyle || 'SYNC',
    step?.correlationOutput || '',
    String(step?.waitTimeoutMillis || 0),
  ].join('\u0000')
}

function sameMembers<T>(left: T[], right: T[], signature: (value: T) => string) {
  if (left.length !== right.length) return false
  const published = right.map(signature).sort()
  return left
    .map(signature)
    .sort()
    .every((value, index) => value === published[index])
}
