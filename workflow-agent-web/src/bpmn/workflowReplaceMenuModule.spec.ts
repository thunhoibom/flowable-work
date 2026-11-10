import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BpmnElement, ModdleElement } from './modeler-types'
import { EventWaitReplaceMenuProvider } from './workflowReplaceMenuModule'

const flags = vi.hoisted(() => ({ eventsEnabled: true }))

vi.mock('@/config/product', () => ({
  callActivityEnabled: true,
  get eventsEnabled() {
    return flags.eventsEnabled
  },
}))

interface Entry {
  label: string
  action: () => void
}

function setup() {
  const popupMenu = {
    registerProvider: vi.fn<(id: string, priority: number, provider: unknown) => void>(),
  }
  const replaced: BpmnElement = { id: 'new', businessObject: { $type: 'bpmn:BoundaryEvent' } }
  const bpmnReplace = {
    replaceElement: vi.fn<(element: BpmnElement, target: Record<string, unknown>) => BpmnElement>(
      () => replaced,
    ),
  }
  const modeling = {
    updateModdleProperties:
      vi.fn<
        (element: BpmnElement, object: ModdleElement, properties: Record<string, unknown>) => void
      >(),
    updateProperties: vi.fn<(element: BpmnElement, properties: Record<string, unknown>) => void>(),
  }
  const moddle = {
    create: (type: string, properties?: Record<string, unknown>) => ({
      $type: type,
      ...properties,
    }),
  }
  const provider = new EventWaitReplaceMenuProvider(popupMenu, bpmnReplace, modeling, moddle)
  const entries = (businessObject: ModdleElement) =>
    provider.getPopupMenuEntries({ businessObject })({}) as Record<string, Entry>
  return { popupMenu, bpmnReplace, modeling, entries, replaced }
}

describe('EventWaitReplaceMenuProvider', () => {
  beforeEach(() => {
    flags.eventsEnabled = true
  })

  it('replaces a timer boundary keeping cancelActivity, then writes the event extensions', () => {
    const { bpmnReplace, modeling, entries, replaced } = setup()

    const entry = entries({
      $type: 'bpmn:BoundaryEvent',
      cancelActivity: false,
      eventDefinitions: [{ $type: 'bpmn:TimerEventDefinition' }],
    })['replace-with-event-registry-boundary']!
    expect(entry.label).toBe('Sự kiện (Event Registry)')
    entry.action()

    expect(bpmnReplace.replaceElement).toHaveBeenCalledWith(expect.anything(), {
      type: 'bpmn:BoundaryEvent',
      cancelActivity: false,
      eventDefinitionType: undefined,
    })
    expect(modeling.updateModdleProperties).toHaveBeenCalledWith(
      replaced,
      replaced.businessObject,
      expect.objectContaining({
        asyncLeave: true,
        extensionElements: expect.objectContaining({
          values: [{ $type: 'flowable:EventType', body: '' }],
        }),
      }),
    )
  })

  it('offers the catch for intermediate events and nothing when events are off', () => {
    const { entries } = setup()
    expect(entries({ $type: 'bpmn:IntermediateThrowEvent' })).toHaveProperty(
      'replace-with-event-registry-catch',
    )

    flags.eventsEnabled = false
    expect(entries({ $type: 'bpmn:IntermediateThrowEvent' })).toEqual({})
  })

  it('does not offer the entry on an element that already waits for an event', () => {
    const { entries } = setup()
    expect(
      entries({
        $type: 'bpmn:IntermediateCatchEvent',
        extensionElements: { values: [{ $type: 'flowable:EventType', body: 'paid' }] },
      }),
    ).toEqual({})
  })
})
