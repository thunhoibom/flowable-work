# Agent Collaboration Architecture

Product positioning and staged outcomes are defined in the [Product Positioning and Goals](product-positioning-and-goals.zh-CN.md). This document is a cross-repository architecture summary; the backend repository owns the detailed runtime design and implementation plan.

## Design position

Flowable owns BPMN execution and durable business state. The Agent Runtime owns model calls, conversations, governed tool execution, structured output, checkpoints, cost, and run status. Neither side writes directly into the other side's internal tables.

The integration is planned as a durable Outbox/Inbox boundary so model latency and provider failures never hold a Flowable database transaction open.

## Collaboration modes

1. **Autonomous Agent node**: an asynchronous, triggerable Service Task starts an Agent Run and resumes only after a durable result is accepted.
2. **User Task Copilot**: an agent may analyze and draft, while a person retains task-completion authority.
3. **Agent result with human review**: the process explicitly routes an agent result into a User Task before continuing.

## Non-negotiable controls

- Tenant-scoped, encrypted provider credential references; no API keys in BPMN XML.
- Immutable Agent versions bound to a deployed process version.
- Persisted checkpoints around model responses, tool requests, tool results, and human interruptions.
- Idempotent commands, worker leases, retry policy, and same-conversation serialization.
- Per-run limits for iterations, tool calls, tokens, duration, and cost.
- Tool identity, permissions, risk classification, approval, audit, and SSRF controls.
- Database-backed run history with recoverable SSE as a delivery channel, not the source of truth.

## Planned execution boundary

```mermaid
sequenceDiagram
    participant F as Flowable
    participant O as Outbox
    participant A as Agent Runtime
    participant P as Provider / Tool
    participant I as Inbox

    F->>O: Commit AgentRunRequested
    O->>A: Deliver idempotent command
    A->>A: Create run and checkpoint
    A->>P: Invoke model or governed tool
    P-->>A: Result
    A->>I: Commit terminal result
    I->>F: Trigger waiting execution
```

## Design-time collaborative process generation

The planned generator does not ask an LLM to write final BPMN XML. It produces a versioned semantic IR, generates the process skeleton before filling node details, and uses deterministic Flowable model compilation and layout. The release candidate also contains Agent, rule, assignment, knowledge, tool, human-review, failure-policy, and test drafts.

Generated assets remain drafts until static validation, path and scenario tests, Agent evaluations, and authorized human review pass. The generator is a design-time Copilot; it does not become a second workflow engine or share the Agent Runtime state machine.

Detailed backend records:

- [Agent collaboration design](https://github.com/illuseahashmap/workflow-agent-service/blob/main/docs/architecture/agent-collaboration-design.md)
- [Agent MVP implementation plan](https://github.com/illuseahashmap/workflow-agent-service/blob/main/docs/architecture/agent-mvp-implementation-plan.md)
- [Backend project status](https://github.com/illuseahashmap/workflow-agent-service/blob/main/docs/status.md)
