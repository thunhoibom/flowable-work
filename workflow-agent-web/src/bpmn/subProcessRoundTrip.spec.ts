import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from './flowableModdle'
import workflowModdle from './workflowModdle'
import type { ModdleElement } from './modeler-types'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
  <error id="Error_PAY" name="PAY" errorCode="PAY"/>
  <process id="p" isExecutable="true">
    <startEvent id="s"/>
    <subProcess id="review">
      <multiInstanceLoopCharacteristics isSequential="false"
          flowable:collection="\${reviewers}" flowable:elementVariable="reviewer">
        <completionCondition>\${nrOfCompletedInstances &gt;= 2}</completionCondition>
      </multiInstanceLoopCharacteristics>
      <startEvent id="rs"/>
      <subProcess id="nested">
        <startEvent id="ns"/>
        <userTask id="check" flowable:assignee="\${reviewer}"/>
        <endEvent id="ne"/>
      </subProcess>
      <endEvent id="re"/>
    </subProcess>
    <boundaryEvent id="late" attachedToRef="review" cancelActivity="true">
      <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>
    </boundaryEvent>
    <subProcess id="onError" triggeredByEvent="true">
      <startEvent id="es"><errorEventDefinition errorRef="Error_PAY"/></startEvent>
      <endEvent id="ee"/>
    </subProcess>
  </process>
</definitions>`

function all(element: ModdleElement, out: ModdleElement[] = []) {
  out.push(element)
  for (const child of (element.flowElements as ModdleElement[] | undefined) ?? []) all(child, out)
  return out
}

describe('subprocess XML round trip', () => {
  it('keeps multi-instance, nesting, timers and error references', async () => {
    const moddle = new BpmnModdle({ flowable: flowableModdle, workflow: workflowModdle })
    const { rootElement } = await moddle.fromXML(XML)
    const process = (rootElement.rootElements as ModdleElement[]).find(
      (element) => element.$type === 'bpmn:Process',
    )!
    const elements = all(process)
    const review = elements.find((element) => element.id === 'review')!

    // Declared in flowableModdle, so sanitizeAttributes will not strip them on save.
    expect(review.loopCharacteristics?.collection).toBe('${reviewers}')
    expect(review.loopCharacteristics?.$attrs?.['flowable:collection']).toBeUndefined()
    expect(elements.find((element) => element.id === 'check')?.assignee).toBe('${reviewer}')

    const { xml } = await moddle.toXML(rootElement)
    expect(xml).toContain('flowable:collection="${reviewers}"')
    expect(xml).toContain('flowable:elementVariable="reviewer"')
    expect(xml).toContain('<completionCondition')
    expect(xml).toContain('<timeDuration>P3D</timeDuration>')
    expect(xml).toContain('triggeredByEvent="true"')
    expect(xml).toContain('errorRef="Error_PAY"')
    expect(xml).toContain('<subProcess id="nested">')
  })
})
