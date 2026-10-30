import { describe, expect, it } from 'vitest'
import {
  createBlankBpmn,
  listUserTasks,
  readServiceCall,
  resolveStartFrontierKinds,
  resolveTaskMode,
  validateBpmnXml,
  withoutMisplacedAgentTasks,
} from '@/utils/bpmn'

describe('BPMN utilities', () => {
  it('creates an executable plain XML process', () => {
    const xml = createBlankBpmn('expense_approval', '费用审批')
    const document = validateBpmnXml(xml)
    const process = Array.from(document.getElementsByTagName('*')).find(
      (element) => element.localName === 'process',
    )

    expect(process?.getAttribute('id')).toBe('expense_approval')
    expect(process?.getAttribute('name')).toBe('费用审批')
    expect(xml).not.toContain('BASE64')
  })

  it('rejects malformed BPMN XML', () => {
    expect(() => validateBpmnXml('<definitions>')).toThrow('Sơ đồ không đúng định dạng')
  })

  it.each([
    ['singleTask', 'single'],
    ['candidateTask', 'candidate'],
    ['parallelTask', 'parallel'],
  ] as const)('resolves %s mode as %s', (taskId, expected) => {
    const xml = `<?xml version="1.0"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
        <process id="approval" isExecutable="true">
          <userTask id="singleTask" flowable:assignee="demo" />
          <userTask id="candidateTask" flowable:candidateUsers="demo" />
          <userTask id="parallelTask"><multiInstanceLoopCharacteristics /></userTask>
        </process>
      </definitions>`

    expect(resolveTaskMode(xml, taskId)).toBe(expected)
  })

  it('lists user tasks with stable labels and assignment modes', () => {
    const xml = `<?xml version="1.0"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
        <process id="approval" isExecutable="true">
          <userTask id="submitTask" name="提交申请" flowable:assignee="demo" />
          <userTask id="approveTask" name="部门审批" flowable:candidateUsers="manager" />
          <userTask id="archiveTask"><multiInstanceLoopCharacteristics /></userTask>
        </process>
      </definitions>`

    expect(listUserTasks(xml)).toEqual([
      { id: 'submitTask', name: '提交申请', mode: 'single' },
      { id: 'approveTask', name: '部门审批', mode: 'candidate' },
      { id: 'archiveTask', name: 'archiveTask', mode: 'parallel' },
    ])
  })

  it('requires Agent bindings to use the receive-task contract', () => {
    const valid = `<?xml version="1.0"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:workflow="http://workflow-agent.local/bpmn"
        xmlns:flowable="http://flowable.org/bpmn">
        <process id="agent" isExecutable="true">
          <receiveTask id="agentTask"><extensionElements>
            <workflow:agentTask agentVersionId="12" />
            <flowable:ExecutionListener event="start"
              flowable:delegateExpression="\${agentTaskExecutionListener}" />
          </extensionElements></receiveTask>
        </process>
      </definitions>`
    expect(() => validateBpmnXml(valid)).not.toThrow()

    const invalid = valid
      .replace('<receiveTask', '<serviceTask')
      .replace('</receiveTask>', '</serviceTask>')
    const cleaned = withoutMisplacedAgentTasks(invalid)
    expect(cleaned).not.toContain('workflow:agentTask')
    expect(cleaned).not.toContain('agentTaskExecutionListener')
    expect(() => validateBpmnXml(cleaned)).not.toThrow()
    expect(withoutMisplacedAgentTasks(valid)).toContain('workflow:agentTask')

    const copied = `<?xml version="1.0"?>
      <definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:workflow="http://workflow-agent.local/bpmn"
        xmlns:flowable="http://flowable.org/bpmn">
        <bpmn:process id="Process_0cqiom7" isExecutable="true">
          <bpmn:serviceTask id="Activity_0y5ofb7" name="Gửi email">
            <bpmn:extensionElements>
              <workflow:agentTask agentVersionId="" inputMapping="{}" outputMapping="{}" />
              <flowable:executionListener event="start" delegateExpression="\${agentTaskExecutionListener}" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </definitions>`
    const saved = withoutMisplacedAgentTasks(copied)
    expect(saved).not.toContain('workflow:agentTask')
    expect(saved).not.toContain('agentTaskExecutionListener')
    expect(saved).toContain('Activity_0y5ofb7')
    expect(() => validateBpmnXml(saved)).not.toThrow()
  })

  it('stops start-time participant discovery at an Agent wait state', () => {
    const xml = `<?xml version="1.0"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:workflow="http://workflow-agent.local/bpmn">
        <process id="agent" isExecutable="true">
          <startEvent id="start" /><receiveTask id="agentTask"><extensionElements>
            <workflow:agentTask agentVersionId="12" />
          </extensionElements></receiveTask>
          <userTask id="afterAgent" />
          <sequenceFlow sourceRef="start" targetRef="agentTask" />
          <sequenceFlow sourceRef="agentTask" targetRef="afterAgent" />
        </process>
      </definitions>`
    expect(resolveStartFrontierKinds(xml)).toEqual({ hasAgentWait: true, hasUserTask: false })
  })

  it('reads service call keys from the catalog binding', () => {
    const xml = `<?xml version="1.0"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:workflow="http://workflow-agent.local/bpmn">
        <process id="lead" isExecutable="true">
          <serviceTask id="createLead"><extensionElements>
            <workflow:serviceCall serviceKey="crm" operationKey="createLead"
              url="https://crm.example/leads" />
          </extensionElements></serviceTask>
        </process>
      </definitions>`

    expect(readServiceCall(xml)).toEqual({ serviceKey: 'crm', operationKey: 'createLead' })
    expect(readServiceCall(xml)).not.toHaveProperty('url')
  })
})
