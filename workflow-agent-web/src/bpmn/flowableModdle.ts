const flowableModdle = {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
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
