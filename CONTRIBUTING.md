# Contributing

Thank you for considering a contribution to Workflow Agent.

## Before coding

1. Search existing issues and architecture documents.
2. Open a focused issue for behavioral, API, schema, or architecture changes.
3. Identify whether the change belongs to the product repository, backend, or frontend.

## Repository boundaries

- Product documentation, Docker composition, examples, and cross-repository release metadata belong here.
- Java domain logic, Flowable adapters, database migrations, and backend APIs belong in `workflow-agent-service`.
- Vue features, BPMN modeler behavior, and browser tests belong in `workflow-agent-web`.

## Quality baseline

Backend changes must preserve DDD boundaries and pass Maven verification. Frontend changes must pass formatting, linting, type checks, unit tests, and production build. Security-sensitive changes require tests for tenant isolation and permission boundaries.

Keep pull requests small enough to review and document any operational or compatibility impact.
