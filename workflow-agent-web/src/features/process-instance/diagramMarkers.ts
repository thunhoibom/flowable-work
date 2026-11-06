import type { ModdleElement } from '@/bpmn/modeler-types'

/**
 * Subprocesses that contain any of the given activities, innermost first.
 * Marking them lets a collapsed subprocess show that work is happening inside it.
 */
export function enclosingSubProcessIds(
  activityIds: string[],
  lookup: (id: string) => ModdleElement | undefined,
) {
  const result = new Set<string>()
  for (const id of activityIds) {
    let parent = lookup(id)?.$parent as ModdleElement | undefined
    while (parent && parent.$type !== 'bpmn:Process') {
      if (parent.$type === 'bpmn:SubProcess' && parent.id) result.add(parent.id)
      parent = parent.$parent as ModdleElement | undefined
    }
  }
  return [...result]
}
