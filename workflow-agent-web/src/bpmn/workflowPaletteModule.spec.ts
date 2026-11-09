import { describe, expect, it, vi } from 'vitest'
import { WorkflowPaletteProvider } from './workflowPaletteModule'

describe('workflow palette', () => {
  it('offers a wait step that leaves asynchronously', () => {
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
})
