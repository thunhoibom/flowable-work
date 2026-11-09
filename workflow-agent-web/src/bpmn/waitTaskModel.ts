import type { BpmnElement, ModdleElement } from './modeler-types'

function extensionTypes(object?: ModdleElement) {
  return (object?.extensionElements?.values || []).map((value) =>
    String(value.$type || '').toLowerCase(),
  )
}

/** A plain receive task: continued only by "Tiếp tục". Agent and event waits are not manual. */
export function isManualWaitElement(object?: ModdleElement) {
  if (object?.$type !== 'bpmn:ReceiveTask') return false
  const types = extensionTypes(object)
  return !types.includes('workflow:agenttask') && !types.includes('flowable:eventtype')
}

export function hasTimerBoundary(shape?: { attachers?: BpmnElement[] }) {
  return (shape?.attachers || []).some((attacher) =>
    ((attacher.businessObject?.eventDefinitions as ModdleElement[] | undefined) || []).some(
      (definition) => definition.$type === 'bpmn:TimerEventDefinition',
    ),
  )
}

export function waitTaskWarnings(form: { asyncLeave: boolean; hasTimeout: boolean }) {
  const warnings: string[] = []
  if (!form.hasTimeout) {
    warnings.push('Bước chờ chưa có hạn chờ. Công việc có thể chờ mãi nếu không ai bấm tiếp tục.')
  }
  if (!form.asyncLeave) {
    warnings.push(
      'Đã tắt "Rời bước bất đồng bộ": lỗi ở các bước phía sau sẽ làm lệnh tiếp tục thất bại theo.',
    )
  }
  return warnings
}
