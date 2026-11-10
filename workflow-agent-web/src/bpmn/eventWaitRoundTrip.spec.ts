import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from './flowableModdle'
import workflowModdle from './workflowModdle'
import type { ModdleElement } from './modeler-types'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
  <process id="p" isExecutable="true">
    <startEvent id="s"/>
    <receiveTask id="wait" flowable:asyncLeave="true">
      <extensionElements>
        <flowable:eventType>paymentReceived</flowable:eventType>
        <flowable:eventCorrelationParameter name="orderId" value="\${orderId}"/>
        <flowable:eventCorrelationParameter name="region" value="\${region}"/>
        <flowable:eventOutParameter source="note" target="note" transient="true"/>
      </extensionElements>
    </receiveTask>
    <intermediateCatchEvent id="catch" flowable:asyncLeave="true">
      <extensionElements>
        <flowable:eventType>shipped</flowable:eventType>
        <flowable:eventCorrelationParameter name="orderId" value="\${orderId}"/>
      </extensionElements>
    </intermediateCatchEvent>
    <userTask id="work"/>
    <boundaryEvent id="cancelled" attachedToRef="work" cancelActivity="true">
      <extensionElements>
        <flowable:eventType>orderCancelled</flowable:eventType>
        <flowable:eventCorrelationParameter name="orderId" value="\${orderId}"/>
        <flowable:eventOutParameter source="reason" target="cancelReason"/>
      </extensionElements>
    </boundaryEvent>
  </process>
</definitions>`

async function load(xml: string) {
  const moddle = new BpmnModdle({ flowable: flowableModdle, workflow: workflowModdle })
  const { rootElement } = await moddle.fromXML(xml)
  const process = (rootElement.rootElements as ModdleElement[]).find(
    (element) => element.$type === 'bpmn:Process',
  )!
  const byId = (id: string) =>
    (process.flowElements as ModdleElement[]).find((element) => element.id === id)!
  return { moddle, rootElement, byId }
}

function extensions(object: ModdleElement, type: string) {
  return (object.extensionElements?.values ?? []).filter((value) => value.$type === type)
}

describe('event wait XML round trip', () => {
  it('keeps event type, correlations, outputs, asyncLeave and cancelActivity', async () => {
    const first = await load(XML)
    const { xml } = await first.moddle.toXML(first.rootElement)
    const { byId } = await load(xml)

    const wait = byId('wait')
    expect(wait.asyncLeave).toBe(true)
    expect(extensions(wait, 'flowable:EventType')[0]?.body).toBe('paymentReceived')
    expect(
      extensions(wait, 'flowable:EventCorrelationParameter').map((p) => [p.name, p.value]),
    ).toEqual([
      ['orderId', '${orderId}'],
      ['region', '${region}'],
    ])
    expect(
      extensions(wait, 'flowable:EventOutParameter').map((p) => [p.source, p.target, p.transient]),
    ).toEqual([['note', 'note', true]])

    const catchEvent = byId('catch')
    expect(catchEvent.asyncLeave).toBe(true)
    expect(catchEvent.$attrs?.['flowable:asyncLeave']).toBeUndefined()
    expect(extensions(catchEvent, 'flowable:EventType')[0]?.body).toBe('shipped')

    const boundary = byId('cancelled')
    expect(boundary.cancelActivity).toBe(true)
    expect((boundary.attachedToRef as ModdleElement).id).toBe('work')
    expect(extensions(boundary, 'flowable:EventType')[0]?.body).toBe('orderCancelled')
    expect(
      extensions(boundary, 'flowable:EventOutParameter').map((p) => [p.source, p.target]),
    ).toEqual([['reason', 'cancelReason']])
  })
})
