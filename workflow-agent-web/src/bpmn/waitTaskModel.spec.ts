import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from '@/bpmn/flowableModdle'
import workflowModdle from '@/bpmn/workflowModdle'
import type { BpmnElement, ModdleElement } from '@/bpmn/modeler-types'
import { hasTimerBoundary, isManualWaitElement, waitTaskWarnings } from './waitTaskModel'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
  <bpmn:process id="p">
    <bpmn:receiveTask id="wait" flowable:asyncLeave="true" />
    <bpmn:receiveTask id="eventWait">
      <bpmn:extensionElements>
        <flowable:eventType>paid</flowable:eventType>
        <flowable:eventCorrelationParameter name="orderId" value="\${orderId}" />
        <flowable:eventOutParameter source="amount" target="paidAmount" />
      </bpmn:extensionElements>
    </bpmn:receiveTask>
  </bpmn:process>
</bpmn:definitions>`

async function load() {
  const moddle = new BpmnModdle({ flowable: flowableModdle, workflow: workflowModdle })
  const { rootElement } = await moddle.fromXML(XML)
  const process = (rootElement as ModdleElement).rootElements as ModdleElement[]
  const elements = process[0]!.flowElements as ModdleElement[]
  return { moddle, rootElement, byId: (id: string) => elements.find((element) => element.id === id)! }
}

describe('wait task model', () => {
  it('round-trips flowable:asyncLeave and the event registry extensions', async () => {
    const { moddle, rootElement, byId } = await load()
    expect(byId('wait').asyncLeave).toBe(true)
    const { xml } = await moddle.toXML(rootElement)
    expect(xml).toContain('flowable:asyncLeave="true"')
    expect(xml).toContain('<flowable:eventType>paid</flowable:eventType>')
    expect(xml).toContain('<flowable:eventCorrelationParameter name="orderId" value="${orderId}" />')
    expect(xml).toContain('<flowable:eventOutParameter source="amount" target="paidAmount" />')
  })

  it('tells manual waits apart from agent and event waits', async () => {
    const { byId } = await load()
    expect(isManualWaitElement(byId('wait'))).toBe(true)
    expect(isManualWaitElement(byId('eventWait'))).toBe(false)
    expect(
      isManualWaitElement({
        $type: 'bpmn:ReceiveTask',
        extensionElements: { values: [{ $type: 'workflow:AgentTask' }] },
      }),
    ).toBe(false)
    expect(isManualWaitElement({ $type: 'bpmn:UserTask' })).toBe(false)
    expect(isManualWaitElement(undefined)).toBe(false)
  })

  it('detects a timer boundary and warns about missing timeout and asyncLeave', () => {
    const timer: BpmnElement = {
      businessObject: { eventDefinitions: [{ $type: 'bpmn:TimerEventDefinition' }] },
    }
    const message: BpmnElement = {
      businessObject: { eventDefinitions: [{ $type: 'bpmn:MessageEventDefinition' }] },
    }
    expect(hasTimerBoundary({ attachers: [message, timer] })).toBe(true)
    expect(hasTimerBoundary({ attachers: [message] })).toBe(false)
    expect(hasTimerBoundary(undefined)).toBe(false)
    expect(waitTaskWarnings({ asyncLeave: true, hasTimeout: true })).toEqual([])
    expect(waitTaskWarnings({ asyncLeave: false, hasTimeout: false })).toHaveLength(2)
  })
})
