import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkflowPaletteProvider } from './workflowPaletteModule'

const flags = vi.hoisted(() => ({ eventsEnabled: false }))

vi.mock('@/config/product', () => ({
  agentsEnabled: false,
  callActivityEnabled: true,
  scriptTasksEnabled: false,
  get eventsEnabled() {
    return flags.eventsEnabled
  },
}))

function setup() {
  const palette = { registerProvider: vi.fn<(priority: number, provider: unknown) => void>() }
  const create = { start: vi.fn<(event: Event, shape: unknown) => void>() }
  const elementFactory = {
    createShape: vi.fn<(attrs: { type: string; businessObject?: unknown }) => unknown>(
      (attrs) => attrs,
    ),
  }
  const bpmnFactory = {
    create: vi.fn<(type: string, attrs?: Record<string, unknown>) => unknown>((type, attrs) => ({
      $type: type,
      ...attrs,
    })),
  }
  const provider = new WorkflowPaletteProvider(palette, create, elementFactory, bpmnFactory)
  return { provider, create, bpmnFactory }
}

describe('workflow palette', () => {
  beforeEach(() => {
    flags.eventsEnabled = false
  })

  it('offers a wait step that leaves asynchronously', () => {
    const { provider, create, bpmnFactory } = setup()

    const entries = provider.getPaletteEntries()({})
    const entry = entries['create.wait-task']!
    expect(entry.title).toBe('Thêm bước chờ')
    expect(entry.className).toBe('bpmn-icon-receive-task')

    entry.action.click(new Event('click'))
    expect(bpmnFactory.create).toHaveBeenCalledWith('bpmn:ReceiveTask', { asyncLeave: true })
    expect(create.start).toHaveBeenCalledWith(
      expect.any(Event),
      expect.objectContaining({ type: 'bpmn:ReceiveTask' }),
    )
  })

  it('hides the event wait while events are off', () => {
    const { provider } = setup()

    expect(provider.getPaletteEntries()({})['create.event-wait']).toBeUndefined()
  })

  it('offers an event wait catch event when events are on', () => {
    flags.eventsEnabled = true
    const { provider, create } = setup()

    const entry = provider.getPaletteEntries()({})['create.event-wait']!
    expect(entry.title).toBe('Chờ sự kiện')
    expect(entry.className).toBe('bpmn-icon-intermediate-event-none')

    entry.action.click(new Event('click'))
    expect(create.start).toHaveBeenCalledWith(
      expect.any(Event),
      expect.objectContaining({
        type: 'bpmn:IntermediateCatchEvent',
        businessObject: expect.objectContaining({
          asyncLeave: true,
          extensionElements: expect.objectContaining({
            values: [{ $type: 'flowable:EventType', body: '' }],
          }),
        }),
      }),
    )
  })
})
