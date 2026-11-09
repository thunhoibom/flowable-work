import { agentsEnabled, callActivityEnabled, scriptTasksEnabled } from '@/config/product'

interface PaletteEntry {
  group: string
  className: string
  title: string
  action: {
    dragstart: (event: Event) => void
    click: (event: Event) => void
  }
}

interface Palette {
  registerProvider(priority: number, provider: WorkflowPaletteProvider): void
}

interface CreateService {
  start(event: Event, shape: unknown): void
}

interface ElementFactory {
  createShape(properties: { type: string; businessObject?: unknown }): unknown
}

interface BpmnFactory {
  create(type: string, attrs?: Record<string, unknown>): unknown
}

export class WorkflowPaletteProvider {
  static $inject = ['palette', 'create', 'elementFactory', 'bpmnFactory']

  constructor(
    palette: Palette,
    private readonly create: CreateService,
    private readonly elementFactory: ElementFactory,
    private readonly bpmnFactory: BpmnFactory,
  ) {
    palette.registerProvider(500, this)
  }

  getPaletteEntries() {
    return (entries: Record<string, PaletteEntry>) => {
      const createUserTask = (event: Event) => {
        const shape = this.elementFactory.createShape({ type: 'bpmn:UserTask' })
        this.create.start(event, shape)
      }

      entries['create.task'] = {
        group: 'activity',
        className: 'bpmn-icon-user-task',
        title: 'Thêm nhiệm vụ',
        action: {
          dragstart: createUserTask,
          click: createUserTask,
        },
      }

      const createWaitTask = (event: Event) => {
        const businessObject = this.bpmnFactory.create('bpmn:ReceiveTask', { asyncLeave: true })
        const shape = this.elementFactory.createShape({ type: 'bpmn:ReceiveTask', businessObject })
        this.create.start(event, shape)
      }

      entries['create.wait-task'] = {
        group: 'activity',
        className: 'bpmn-icon-receive-task',
        title: 'Thêm bước chờ',
        action: { dragstart: createWaitTask, click: createWaitTask },
      }

      const createScriptTask = (event: Event) => {
        const businessObject = this.bpmnFactory.create('bpmn:ScriptTask', {
          scriptFormat: 'javascript',
        })
        const shape = this.elementFactory.createShape({ type: 'bpmn:ScriptTask', businessObject })
        this.create.start(event, shape)
      }

      if (scriptTasksEnabled) {
        entries['create.script-task'] = {
          group: 'activity',
          className: 'bpmn-icon-script-task',
          title: 'Thêm bước script',
          action: { dragstart: createScriptTask, click: createScriptTask },
        }
      }
      const createCallActivity = (event: Event) => {
        const shape = this.elementFactory.createShape({ type: 'bpmn:CallActivity' })
        this.create.start(event, shape)
      }

      if (callActivityEnabled) {
        entries['create.call-activity'] = {
          group: 'activity',
          className: 'bpmn-icon-call-activity',
          title: 'Gọi quy trình khác',
          action: { dragstart: createCallActivity, click: createCallActivity },
        }
      }

      const subProcess = entries['create.subprocess-expanded']
      if (subProcess) subProcess.title = 'Thêm quy trình con'
      if (!agentsEnabled) delete entries['create.agent-task']
      return entries
    }
  }
}

export default {
  __init__: ['workflowPaletteProvider'],
  workflowPaletteProvider: ['type', WorkflowPaletteProvider],
}
