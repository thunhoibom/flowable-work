import { callActivityEnabled, eventsEnabled } from '@/config/product'
import { canBeEventWait, eventWaitKind } from './eventWaitModel'
import type { BpmnElement, Moddle, Modeling, ModdleElement } from './modeler-types'
import { filterReplaceEntries, type ReplaceTarget } from './subProcessModel'

interface PopupMenu {
  registerProvider(id: string, priority: number, provider: unknown): void
}

interface ReplaceService {
  replaceElement(element: BpmnElement, target: Record<string, unknown>): BpmnElement
}

interface ReplaceEntry {
  label: string
  className: string
  action: () => void
}

/**
 * Runs after the default bpmn-js replace menu and removes options the platform rejects,
 * such as transactions, ad-hoc subprocesses, and unsupported subprocess events.
 */
class WorkflowReplaceMenuProvider {
  static $inject = ['popupMenu']

  constructor(popupMenu: PopupMenu) {
    popupMenu.registerProvider('bpmn-replace', 500, this)
  }

  getPopupMenuEntries(target: ReplaceTarget) {
    return <T>(entries: Record<string, T>) =>
      filterReplaceEntries(target, entries, { callActivity: callActivityEnabled })
  }
}

/**
 * Adds the Event Registry waits (slice 5) to the replace menu of intermediate and boundary events.
 * Registered before the filter above, so the filter still decides for subprocess boundaries.
 */
export class EventWaitReplaceMenuProvider {
  static $inject = ['popupMenu', 'bpmnReplace', 'modeling', 'moddle']

  constructor(
    popupMenu: PopupMenu,
    private readonly bpmnReplace: ReplaceService,
    private readonly modeling: Modeling,
    private readonly moddle: Moddle,
  ) {
    popupMenu.registerProvider('bpmn-replace', 600, this)
  }

  getPopupMenuEntries(target: BpmnElement) {
    return (entries: Record<string, unknown>) => {
      const object = target.businessObject
      if (!eventsEnabled || !object || eventWaitKind(object)) return entries
      if (
        object.$type === 'bpmn:IntermediateCatchEvent' ||
        object.$type === 'bpmn:IntermediateThrowEvent'
      ) {
        return {
          ...entries,
          'replace-with-event-registry-catch': this.entry(
            'Chờ sự kiện (Event Registry)',
            'bpmn-icon-intermediate-event-none',
            target,
            { type: 'bpmn:IntermediateCatchEvent' },
          ),
        }
      }
      if (object.$type === 'bpmn:BoundaryEvent') {
        return {
          ...entries,
          'replace-with-event-registry-boundary': this.entry(
            'Sự kiện (Event Registry)',
            'bpmn-icon-intermediate-event-none',
            target,
            { type: 'bpmn:BoundaryEvent', cancelActivity: object.cancelActivity !== false },
          ),
        }
      }
      return entries
    }
  }

  private entry(
    label: string,
    className: string,
    target: BpmnElement,
    replacement: Record<string, unknown>,
  ): ReplaceEntry {
    return {
      label,
      className,
      action: () => {
        const element = canBeEventWait(target.businessObject)
          ? target
          : this.bpmnReplace.replaceElement(target, {
              ...replacement,
              eventDefinitionType: undefined,
            })
        const object = element.businessObject as ModdleElement
        const kept = (object.extensionElements?.values ?? []).filter(
          (value) => value.$type !== 'flowable:EventType',
        )
        this.modeling.updateModdleProperties(element, object, {
          asyncLeave: true,
          extensionElements: this.moddle.create('bpmn:ExtensionElements', {
            values: [this.moddle.create('flowable:EventType', { body: '' }), ...kept],
          }),
        })
      },
    }
  }
}

export default {
  __init__: ['workflowReplaceMenuProvider', 'eventWaitReplaceMenuProvider'],
  workflowReplaceMenuProvider: ['type', WorkflowReplaceMenuProvider],
  eventWaitReplaceMenuProvider: ['type', EventWaitReplaceMenuProvider],
}
