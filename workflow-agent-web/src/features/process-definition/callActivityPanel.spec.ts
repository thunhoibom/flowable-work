import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from '@/bpmn/flowableModdle'
import type { ModdleElement } from '@/bpmn/modeler-types'
import {
  callActivityWarnings,
  readCallActivity,
  suggestedInRows,
  toParameters,
  type CallActivityForm,
} from './callActivityPanel'

const form = (patch: Partial<CallActivityForm> = {}): CallActivityForm => ({
  calledElement: 'payment',
  businessKeyMode: 'none',
  businessKey: '',
  idVariableName: '',
  inRows: [],
  outRows: [],
  ...patch,
})

describe('toParameters', () => {
  it('writes expressions as sourceExpression and plain names as source', () => {
    expect(
      toParameters([
        { source: 'orderId', target: 'orderId' },
        { source: '${amount * 2}', target: 'doubled' },
        { source: ' ', target: ' ' },
      ]),
    ).toEqual([
      { source: 'orderId', target: 'orderId' },
      { sourceExpression: '${amount * 2}', target: 'doubled' },
    ])
  })
})

describe('readCallActivity', () => {
  it('reads every field the panel edits', async () => {
    const moddle = new BpmnModdle({ flowable: flowableModdle })
    const { rootElement } = await moddle.fromXML(`<?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                   xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
        <process id="p">
          <callActivity id="call" calledElement="payment" flowable:inheritBusinessKey="true"
                        flowable:idVariableName="paymentId" flowable:completeAsync="true">
            <extensionElements>
              <flowable:in source="orderId" target="orderId"/>
              <flowable:in sourceExpression="\${amount * 2}" target="doubled"/>
              <flowable:out source="status" target="paymentStatus"/>
            </extensionElements>
          </callActivity>
        </process>
      </definitions>`)
    const process = (rootElement.rootElements as ModdleElement[])[0]!
    const call = (process.flowElements as ModdleElement[])[0]

    expect(readCallActivity(call)).toEqual({
      calledElement: 'payment',
      businessKeyMode: 'inherit',
      businessKey: '',
      idVariableName: 'paymentId',
      inRows: [
        { source: 'orderId', target: 'orderId' },
        { source: '${amount * 2}', target: 'doubled' },
      ],
      outRows: [{ source: 'status', target: 'paymentStatus' }],
    })
    expect(call?.$attrs?.['flowable:idVariableName']).toBeUndefined()
  })
})

describe('callActivityWarnings', () => {
  it('accepts a complete form', () => {
    expect(
      callActivityWarnings(
        form({ inRows: [{ source: 'orderId', target: 'orderId' }], idVariableName: 'paymentId' }),
      ),
    ).toEqual([])
  })

  it('mirrors the publish rules', () => {
    expect(
      callActivityWarnings(
        form({
          calledElement: '',
          businessKeyMode: 'expression',
          inRows: [
            { source: 'a', target: 'tenantId' },
            { source: 'b', target: 'x' },
            { source: 'c', target: 'x' },
            { source: '', target: 'y' },
          ],
          outRows: [{ source: 'd', target: 'review_assignee' }],
          idVariableName: '1bad',
        }),
      ),
    ).toEqual([
      'Hãy chọn quy trình được gọi',
      'Cần nhập biểu thức business key',
      'Dữ liệu truyền vào: tenantId do hệ thống quản lý',
      'Dữ liệu truyền vào: x bị trùng',
      'Dữ liệu truyền vào: dòng y thiếu nguồn',
      'Dữ liệu nhận về: review_assignee do hệ thống quản lý',
      'Tên biến lưu id instance con không hợp lệ',
    ])
  })
})

describe('suggestedInRows', () => {
  it('adds required start inputs that are not mapped yet', () => {
    expect(
      suggestedInRows(
        [
          { variablePath: 'order.amount', label: 'Số tiền', required: true },
          { variablePath: 'customerId', label: 'Khách hàng', required: true },
          { variablePath: 'note', label: 'Ghi chú', required: false },
          { variablePath: 'order.currency', label: 'Tiền tệ', required: true },
        ],
        [{ source: 'customerId', target: 'customerId' }],
      ),
    ).toEqual([{ source: 'order', target: 'order' }])
  })
})
