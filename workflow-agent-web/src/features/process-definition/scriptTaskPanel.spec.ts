import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from '@/bpmn/flowableModdle'
import type { ModdleElement } from '@/bpmn/modeler-types'
import {
  fromInParameters,
  isAutomaticTask,
  scriptTaskWarnings,
  toInParameters,
} from './scriptTaskPanel'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
  <bpmn:process id="p">
    <bpmn:scriptTask id="s" scriptFormat="javascript" flowable:resultVariable="total">
      <bpmn:extensionElements>
        <flowable:in source="amount" target="amount" />
        <flowable:in sourceExpression="\${order.qty}" target="qty" />
      </bpmn:extensionElements>
      <bpmn:script>amount * qty</bpmn:script>
    </bpmn:scriptTask>
  </bpmn:process>
</bpmn:definitions>`

describe('script task panel', () => {
  it('maps rows to flowable:in and back', () => {
    const parameters = toInParameters([
      { target: ' amount ', source: 'amount' },
      { target: 'qty', source: '${order.qty}' },
      { target: '', source: '' },
    ])
    expect(parameters).toEqual([
      { target: 'amount', source: 'amount' },
      { target: 'qty', sourceExpression: '${order.qty}' },
    ])
    expect(fromInParameters(parameters)).toEqual([
      { target: 'amount', source: 'amount' },
      { target: 'qty', source: '${order.qty}' },
    ])
  })

  it('warns about rows the backend will reject', () => {
    expect(scriptTaskWarnings('a', [{ target: 'a', source: 'a' }], 'total')).toEqual([])
    expect(scriptTaskWarnings('  ', [], '')).toEqual(['Script đang trống'])
    expect(
      scriptTaskWarnings(
        'a',
        [
          { target: 'a-b', source: 'a' },
          { target: 'x', source: 'a' },
          { target: 'x', source: 'b.c' },
        ],
        '1bad',
      ),
    ).toEqual([
      'Tên đầu vào không hợp lệ: a-b',
      'Tên đầu vào bị trùng: x',
      'Nguồn không hợp lệ cho x',
      'Tên biến kết quả không hợp lệ: 1bad',
    ])
  })

  it('treats script and service tasks as automatic', () => {
    expect(isAutomaticTask('bpmn:ScriptTask')).toBe(true)
    expect(isAutomaticTask('bpmn:ServiceTask')).toBe(true)
    expect(isAutomaticTask('bpmn:UserTask')).toBe(false)
  })

  it('keeps Flowable script attributes through moddle', async () => {
    const moddle = new BpmnModdle({ flowable: flowableModdle })
    const { rootElement } = await moddle.fromXML(XML)
    const process = (rootElement.rootElements as ModdleElement[])[0]
    const task = ((process?.flowElements ?? []) as ModdleElement[])[0]

    expect(task?.resultVariable).toBe('total')
    expect(task?.$attrs?.['flowable:resultVariable']).toBeUndefined()
    expect(task?.extensionElements?.values?.map((value) => value.$type)).toEqual([
      'flowable:In',
      'flowable:In',
    ])

    const { xml } = await moddle.toXML(rootElement)
    expect(xml).toContain('flowable:resultVariable="total"')
    expect(xml).toContain('<flowable:in source="amount" target="amount" />')
    expect(xml).toContain('sourceExpression="${order.qty}"')
  })
})
