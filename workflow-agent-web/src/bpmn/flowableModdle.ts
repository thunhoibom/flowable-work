const flowableModdle = {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
    {
      name: 'AsyncLeave',
      extends: ['bpmn:Activity'],
      properties: [{ name: 'asyncLeave', isAttr: true, type: 'Boolean' }],
    },
    {
      name: 'Assignable',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'MultiInstance',
      extends: ['bpmn:MultiInstanceLoopCharacteristics'],
      properties: [
        { name: 'collection', isAttr: true, type: 'String' },
        { name: 'elementVariable', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'ServiceTask',
      extends: ['bpmn:ServiceTask'],
      properties: [
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'triggerable', isAttr: true, type: 'Boolean' },
        { name: 'async', isAttr: true, type: 'Boolean' },
      ],
    },
    {
      name: 'ScriptTask',
      extends: ['bpmn:ScriptTask'],
      properties: [
        { name: 'resultVariable', isAttr: true, type: 'String' },
        { name: 'autoStoreVariables', isAttr: true, type: 'Boolean' },
      ],
    },
    {
      name: 'CallActivity',
      extends: ['bpmn:CallActivity'],
      properties: [
        { name: 'calledElementType', isAttr: true, type: 'String' },
        { name: 'businessKey', isAttr: true, type: 'String' },
        { name: 'inheritBusinessKey', isAttr: true, type: 'Boolean' },
        { name: 'idVariableName', isAttr: true, type: 'String' },
        { name: 'completeAsync', isAttr: true, type: 'Boolean' },
        { name: 'processInstanceName', isAttr: true, type: 'String' },
        { name: 'useLocalScopeForOutParameters', isAttr: true, type: 'Boolean' },
        { name: 'async', isAttr: true, type: 'Boolean' },
      ],
    },
    {
      name: 'In',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'sourceExpression', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'Out',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'sourceExpression', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
      ],
    },
    // Event registry extensions (Flowable 8 BpmnXMLConstants). Declared so they survive a designer
    // round trip: bpmn-moddle drops undeclared flowable:* elements, which would silently turn an
    // event wait into a manual wait. The backend guard decides whether they may be published.
    {
      name: 'EventType',
      superClass: ['Element'],
      properties: [{ name: 'body', isBody: true, type: 'String' }],
    },
    {
      name: 'EventCorrelationParameter',
      superClass: ['Element'],
      properties: [
        { name: 'name', isAttr: true, type: 'String' },
        { name: 'value', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'EventOutParameter',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
        { name: 'transient', isAttr: true, type: 'Boolean' },
      ],
    },
    {
      name: 'TaskListener',
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'ExecutionListener',
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
      ],
    },
  ],
}

export default flowableModdle
