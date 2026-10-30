const workflowModdle = {
  name: 'WorkflowAgent',
  uri: 'http://workflow-agent.local/bpmn',
  prefix: 'workflow',
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
    {
      name: 'AgentTask',
      superClass: ['Element'],
      properties: [
        { name: 'agentVersionId', isAttr: true, type: 'String' },
        { name: 'inputMapping', isAttr: true, type: 'String' },
        { name: 'outputMapping', isAttr: true, type: 'String' },
        { name: 'failurePolicy', isAttr: true, type: 'String' },
        { name: 'timeoutSeconds', isAttr: true, type: 'String' },
        { name: 'processFailurePolicy', isAttr: true, type: 'String' },
        { name: 'processWaitTimeoutSeconds', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'ServiceCall',
      superClass: ['Element'],
      properties: [
        { name: 'serviceKey', isAttr: true, type: 'String' },
        { name: 'operationKey', isAttr: true, type: 'String' },
        { name: 'inputMapping', isAttr: true, type: 'String' },
        { name: 'outputMapping', isAttr: true, type: 'String' },
      ],
    },
  ],
}

export default workflowModdle
