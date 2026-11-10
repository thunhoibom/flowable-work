import { describe, expect, it } from 'vitest'
import {
  canBeEventWait,
  eventWaitExtensions,
  eventWaitKind,
  eventWaitProblems,
  eventWaitWarnings,
  readEventWait,
  withoutEventExtensions,
  type EventWaitEvent,
  type EventWaitForm,
} from './eventWaitModel'
import type { Moddle, ModdleElement } from './modeler-types'

const moddle: Moddle = { create: (type, properties) => ({ $type: type, ...properties }) }

const PAYMENT: EventWaitEvent = {
  key: 'paymentReceived',
  fields: [],
  publishedFields: [
    { name: 'orderId', correlation: true },
    { name: 'region', correlation: true },
    { name: 'amount', correlation: false },
  ],
}

const LISTENER: ModdleElement = {
  $type: 'flowable:ExecutionListener',
  event: 'start',
  delegateExpression: '${audit}',
}

function element(type: string, values: ModdleElement[], extra: Record<string, unknown> = {}) {
  return { $type: type, extensionElements: { $type: 'bpmn:ExtensionElements', values }, ...extra }
}

const EVENT_TYPE = { $type: 'flowable:EventType', body: ' paymentReceived ' }

function form(overrides: Partial<EventWaitForm> = {}): EventWaitForm {
  return {
    eventKey: 'paymentReceived',
    correlations: [
      { name: 'orderId', value: '${orderId}' },
      { name: 'region', value: '${region}' },
    ],
    outputs: [],
    ...overrides,
  }
}

describe('eventWaitKind', () => {
  it('detects the three kinds', () => {
    expect(eventWaitKind(element('bpmn:ReceiveTask', [EVENT_TYPE]))).toBe('receiveTask')
    expect(eventWaitKind(element('bpmn:IntermediateCatchEvent', [EVENT_TYPE]))).toBe(
      'intermediateCatch',
    )
    expect(eventWaitKind(element('bpmn:BoundaryEvent', [EVENT_TYPE]))).toBe('boundary')
  })

  it('does not take a timer event or a plain receive task for an event wait', () => {
    const timer = element('bpmn:IntermediateCatchEvent', [EVENT_TYPE], {
      eventDefinitions: [{ $type: 'bpmn:TimerEventDefinition' }],
    })
    expect(eventWaitKind(timer)).toBeUndefined()
    expect(canBeEventWait(timer)).toBe(false)
    expect(eventWaitKind({ $type: 'bpmn:ReceiveTask' })).toBeUndefined()
    expect(canBeEventWait({ $type: 'bpmn:IntermediateCatchEvent' })).toBe(true)
  })
})

describe('read and write', () => {
  it('reads the form and writes it back keeping an execution listener', () => {
    const object = element('bpmn:ReceiveTask', [
      EVENT_TYPE,
      { $type: 'flowable:EventCorrelationParameter', name: 'orderId', value: '${orderId}' },
      { $type: 'flowable:EventOutParameter', source: 'amount', target: 'paid', transient: true },
      LISTENER,
    ])

    const read = readEventWait(object)
    expect(read).toEqual({
      eventKey: 'paymentReceived',
      correlations: [{ name: 'orderId', value: '${orderId}' }],
      outputs: [{ source: 'amount', target: 'paid', transient: true }],
    })

    const written = eventWaitExtensions(moddle, object.extensionElements.values, {
      ...read,
      outputs: [...read.outputs, { source: '', target: '', transient: false }],
    })
    expect(written.map((value) => value.$type)).toEqual([
      'flowable:EventType',
      'flowable:EventCorrelationParameter',
      'flowable:EventOutParameter',
      'flowable:ExecutionListener',
    ])
    expect(written[0]?.body).toBe('paymentReceived')
    expect(written[3]).toBe(LISTENER)
  })

  it('switching back to manual removes only the event extensions', () => {
    const values = [
      EVENT_TYPE,
      { $type: 'flowable:EventCorrelationParameter', name: 'orderId', value: '${orderId}' },
      LISTENER,
    ]
    expect(withoutEventExtensions(values)).toEqual([LISTENER])
  })
})

describe('eventWaitProblems', () => {
  it('accepts a valid wait', () => {
    expect(eventWaitProblems(form(), PAYMENT)).toEqual([])
  })

  it('reports the key', () => {
    expect(eventWaitProblems(form({ eventKey: '' }), PAYMENT)).toEqual(['Chưa chọn sự kiện'])
    expect(eventWaitProblems(form({ eventKey: '${key}' }), PAYMENT)).toEqual([
      'Mã sự kiện phải là mã cố định hợp lệ',
    ])
    expect(eventWaitProblems(form({ eventKey: 'unknown' }))).toEqual([
      'Sự kiện unknown chưa được phát hành',
    ])
  })

  it('reports correlations', () => {
    expect(eventWaitProblems(form({ correlations: [] }), PAYMENT)).toEqual([
      'Phải có ít nhất một trường tương quan',
    ])
    expect(
      eventWaitProblems(
        form({
          correlations: [
            { name: 'orderId', value: '${orderId}' },
            { name: 'orderId', value: '${orderId}' },
            { name: 'amount', value: '${amount}' },
            { name: 'region', value: ' ' },
          ],
        }),
        PAYMENT,
      ),
    ).toEqual([
      'Trường tương quan orderId bị lặp',
      'Sự kiện paymentReceived không có trường tương quan amount',
      'Trường tương quan region chưa có giá trị',
    ])
  })

  it('reports outputs', () => {
    expect(
      eventWaitProblems(
        form({
          outputs: [
            { source: 'missing', target: 'x', transient: false },
            { source: 'amount', target: '1bad', transient: false },
            { source: 'amount', target: 'tenantId', transient: false },
            { source: 'amount', target: 'paid', transient: false },
            { source: 'region', target: 'paid', transient: false },
          ],
        }),
        PAYMENT,
      ),
    ).toEqual([
      'Sự kiện paymentReceived không có trường missing',
      'Tên biến 1bad không hợp lệ',
      'Biến tenantId do hệ thống quản lý',
      'Biến paid được gán hai lần',
    ])
  })
})

describe('eventWaitWarnings', () => {
  it('has no warning for a complete wait', () => {
    expect(
      eventWaitWarnings({ form: form(), event: PAYMENT, asyncLeave: true, hasTimeout: true }),
    ).toEqual([])
  })

  it('warns about an unused correlation field (R-B4)', () => {
    const warnings = eventWaitWarnings({
      form: form({ correlations: [{ name: 'orderId', value: '${orderId}' }] }),
      event: PAYMENT,
      asyncLeave: true,
    })
    expect(warnings).toEqual([expect.stringContaining('Trường tương quan region')])
  })

  it('warns when a multi-instance value ignores the element variable (R-B5)', () => {
    const base = { event: PAYMENT, asyncLeave: true, multiInstanceElementVariable: 'item' }
    expect(eventWaitWarnings({ ...base, form: form() })).toEqual([
      expect.stringContaining('${item}'),
    ])
    expect(
      eventWaitWarnings({
        ...base,
        form: form({
          correlations: [
            { name: 'orderId', value: '${item.orderId}' },
            { name: 'region', value: '${item.region}' },
          ],
        }),
      }),
    ).toEqual([])
  })

  it('warns about a missing timeout and asyncLeave off', () => {
    const warnings = eventWaitWarnings({
      form: form(),
      event: PAYMENT,
      asyncLeave: false,
      hasTimeout: false,
    })
    expect(warnings).toEqual([
      expect.stringContaining('chưa có hạn chờ'),
      expect.stringContaining('Rời bước bất đồng bộ'),
    ])
  })
})
