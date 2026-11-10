import { describe, expect, it } from 'vitest'
import type { ModdleElement } from './modeler-types'
import {
  eventKind,
  filterReplaceEntries,
  isEventSubProcess,
  multiInstanceWarnings,
  readMultiInstance,
  readTimer,
  timerWarnings,
} from './subProcessModel'

const entries = (...ids: string[]) => Object.fromEntries(ids.map((id) => [id, { id }]))

describe('readMultiInstance', () => {
  it('is disabled without loop characteristics', () => {
    expect(readMultiInstance({ $type: 'bpmn:SubProcess' })).toEqual({
      enabled: false,
      sequential: false,
      collection: '',
      elementVariable: '',
      completionCondition: '',
    })
  })

  it('reads flowable collection, element variable and completion condition', () => {
    const subProcess: ModdleElement = {
      $type: 'bpmn:SubProcess',
      loopCharacteristics: {
        $type: 'bpmn:MultiInstanceLoopCharacteristics',
        isSequential: true,
        collection: '${reviewers}',
        elementVariable: 'reviewer',
        completionCondition: { $type: 'bpmn:FormalExpression', body: '${done}' },
      },
    }
    expect(readMultiInstance(subProcess)).toEqual({
      enabled: true,
      sequential: true,
      collection: '${reviewers}',
      elementVariable: 'reviewer',
      completionCondition: '${done}',
    })
  })
})

describe('multiInstanceWarnings', () => {
  const base = {
    enabled: true,
    sequential: false,
    collection: '',
    elementVariable: '',
    completionCondition: '',
  }

  it('requires a collection and a valid element variable', () => {
    expect(multiInstanceWarnings(base)).toEqual([
      'Cần nhập danh sách để lặp',
      'Tên biến phần tử không hợp lệ',
    ])
    expect(
      multiInstanceWarnings({ ...base, collection: '${items}', elementVariable: 'item' }),
    ).toEqual([])
  })

  it('says nothing when multi-instance is off', () => {
    expect(multiInstanceWarnings({ ...base, enabled: false })).toEqual([])
  })
})

describe('event helpers', () => {
  it('recognises event subprocesses', () => {
    expect(isEventSubProcess({ $type: 'bpmn:SubProcess', triggeredByEvent: true })).toBe(true)
    expect(isEventSubProcess({ $type: 'bpmn:SubProcess' })).toBe(false)
  })

  it('reads the first event definition kind', () => {
    expect(eventKind({ eventDefinitions: [{ $type: 'bpmn:TimerEventDefinition' }] })).toBe('timer')
    expect(eventKind({ eventDefinitions: [{ $type: 'bpmn:ErrorEventDefinition' }] })).toBe('error')
    expect(
      eventKind({ eventDefinitions: [{ $type: 'bpmn:MessageEventDefinition' }] }),
    ).toBeUndefined()
    expect(eventKind({})).toBeUndefined()
  })

  it('reads whichever timer field is set', () => {
    expect(readTimer({ timeCycle: { body: 'R/PT1H' } })).toEqual({
      kind: 'timeCycle',
      value: 'R/PT1H',
    })
    expect(readTimer({})).toEqual({ kind: 'timeDuration', value: '' })
  })

  it('checks ISO-8601 timer values', () => {
    expect(timerWarnings({ kind: 'timeDuration', value: 'P3D' })).toEqual([])
    expect(timerWarnings({ kind: 'timeDuration', value: '3 days' })).toEqual([
      'Thời hạn phải theo ISO-8601, ví dụ P3D hoặc PT4H',
    ])
    expect(timerWarnings({ kind: 'timeDuration', value: '${dueIn}' })).toEqual([])
    expect(timerWarnings({ kind: 'timeCycle', value: '' })).toEqual(['Cần nhập giá trị hẹn giờ'])
  })
})

describe('filterReplaceEntries', () => {
  it('drops transactions, ad-hoc subprocesses and call activities', () => {
    const task = { businessObject: { $type: 'bpmn:UserTask' } }
    expect(
      Object.keys(
        filterReplaceEntries(
          task,
          entries(
            'replace-with-service-task',
            'replace-with-transaction',
            'replace-with-ad-hoc-subprocess',
            'replace-with-collapsed-ad-hoc-subprocess',
            'replace-with-call-activity',
            'replace-with-subprocess',
          ),
        ),
      ),
    ).toEqual(['replace-with-service-task', 'replace-with-subprocess'])
  })

  it('keeps call activities when enabled', () => {
    const task = { businessObject: { $type: 'bpmn:Task' } }
    expect(
      Object.keys(
        filterReplaceEntries(task, entries('replace-with-call-activity'), { callActivity: true }),
      ),
    ).toEqual(['replace-with-call-activity'])
  })

  it('limits event subprocess starts to error and timer', () => {
    const start = {
      businessObject: {
        $type: 'bpmn:StartEvent',
        $parent: { $type: 'bpmn:SubProcess', triggeredByEvent: true },
      },
    }
    expect(
      Object.keys(
        filterReplaceEntries(
          start,
          entries(
            'replace-with-none-start-event',
            'replace-with-message-start',
            'replace-with-timer-start',
            'replace-with-non-interrupting-timer-start',
            'replace-with-error-start',
            'replace-with-signal-start',
          ),
        ),
      ),
    ).toEqual([
      'replace-with-timer-start',
      'replace-with-non-interrupting-timer-start',
      'replace-with-error-start',
    ])
  })

  it('limits subprocess boundaries to timer, error and Event Registry', () => {
    const boundary = {
      businessObject: {
        $type: 'bpmn:BoundaryEvent',
        attachedToRef: { $type: 'bpmn:SubProcess' },
      },
    }
    expect(
      Object.keys(
        filterReplaceEntries(
          boundary,
          entries(
            'replace-with-message-boundary',
            'replace-with-event-registry-boundary',
            'replace-with-timer-boundary',
            'replace-with-non-interrupting-timer-boundary',
            'replace-with-error-boundary',
            'replace-with-escalation-boundary',
          ),
        ),
      ),
    ).toEqual([
      'replace-with-event-registry-boundary',
      'replace-with-timer-boundary',
      'replace-with-non-interrupting-timer-boundary',
      'replace-with-error-boundary',
    ])
  })

  it('leaves boundaries on tasks alone', () => {
    const boundary = {
      businessObject: { $type: 'bpmn:BoundaryEvent', attachedToRef: { $type: 'bpmn:UserTask' } },
    }
    expect(
      Object.keys(filterReplaceEntries(boundary, entries('replace-with-message-boundary'))),
    ).toEqual(['replace-with-message-boundary'])
  })
})
