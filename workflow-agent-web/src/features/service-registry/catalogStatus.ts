import type { CatalogInputParameter, CatalogOperation, CatalogOutputParameter } from './api'

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
    !sameOutputs(operation.outputParameters, operation.publishedOutputParameters)
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
  return sameMembers(left, published, (row) => [row.name, row.type, row.jsonField].join('\u0000'))
}

function sameMembers<T>(left: T[], right: T[], signature: (value: T) => string) {
  if (left.length !== right.length) return false
  const published = right.map(signature).sort()
  return left
    .map(signature)
    .sort()
    .every((value, index) => value === published[index])
}
