# workflow-agent-service

Backend for Workflow Agent, an open-source, Java-first enterprise Human-Agent Workflow platform built on Flowable OSS.

## Baseline

- JDK 25
- Maven 3.9.x
- Spring Boot 4.x
- Flowable 8.0.0
- PostgreSQL
- Redis
- Flyway

## Modules

```text
workflow-agent-service
|-- pom.xml
|-- shared-kernel    Shared response, exception, context, and principal contracts
|-- auth-engine      Keycloak SSO sign-in, EIM roles and menu, tenants, tenant membership
|-- workflow-engine  Workflow domain, use cases, and Flowable adapters
|-- rules-engine      Reusable condition and priority rule engine
|-- agent-engine      Agent definitions, reliable runtime, providers, retrieval, and guardrails
|-- platform-migrations Ordered platform schema and cross-context integrity migrations
`-- workflow-boot    Executable application and module composition root
```

Module dependency direction:

```text
workflow-boot --> auth-engine --> shared-kernel
workflow-boot --> workflow-engine --> shared-kernel
workflow-boot --> platform-migrations
workflow-engine --> rules-engine
agent-engine --> shared-kernel
```

`auth-engine`, `workflow-engine`, and `agent-engine` follow bounded-context-oriented DDD. The executable application,
HTTP security chain, global exception translation, and Flyway composition live only in `workflow-boot`.
All ordered schema changes live in `platform-migrations`; business modules do not coordinate global Flyway versions.

```text
io.github.illuseahashmap.workflow
|-- process
|   |-- interfaces.rest
|   |-- application
|   `-- infrastructure.flowable / infrastructure.lock
|-- assignment
|   |-- interfaces.rest
|   |-- application
|   |-- domain
|   `-- infrastructure.flowable / infrastructure.persistence
|-- security
|   |-- application.port
|   |-- domain
|   `-- infrastructure.persistence / infrastructure.token / infrastructure.web
`-- config
```

Flowable, JDBC, Redis, and servlet APIs remain in infrastructure adapters. Application contracts describe use cases, and domain packages contain workflow-independent concepts and repository ports.

`shared-kernel` contains dependency-light contracts that can be used by multiple engines, such as API responses, business exceptions, tenant context, and current principal models.

`auth-engine` owns browser/user authentication (Keycloak SSO), tenant lifecycle and membership, and the EIM menu. User roles are not stored locally.
The existing `workflow-engine.security.infrastructure.token` package remains the service-to-service token adapter until a dedicated gateway or service-auth module is introduced.

## Documentation

- [Documentation index](docs/README.md)
- [Product positioning and goals](https://github.com/illuseahashmap/workflow-agent/blob/main/docs/product-positioning-and-goals.zh-CN.md)
- [Project status and next goals](docs/status.md)
- [Agent collaboration architecture](docs/architecture/agent-collaboration-design.md)
- [Agent MVP implementation plan](docs/architecture/agent-mvp-implementation-plan.md)
- [Backend architecture governance and roadmap](docs/quality/architecture-governance-and-roadmap.md)
- [Current issues and next remediation](docs/quality/known-issues.md)
- [OpenAPI contract](docs/api/openapi.yaml)
- [Agent learning knowledge base](docs/learning/agent-development-01-runtime-ledger-and-state-machine.md)

## Capabilities

- process deployment, version activation, definition queries, diagrams, and deletion
- process start, status, task approval, rejection, transfer, and automatic completion
- process instance paging, detail, variables, termination, and highlighted diagrams
- Keycloak SSO login (realm `rsa`, client `VETC.WORKFLOW`); tenant chosen per request with `X-Tenant-Code`
- role checks from the token's `eim_roles` claim and a menu from the EIM sitemap, as in rsa-core-service
- tenant management and Flowable tenant isolation
- conditional node assignment rules, historical-version inheritance, and assignee resolution
- per-client encrypted service tokens with tenant/path authorization, request binding, expiration, and replay protection

## Security Contract

Users sign in only through Keycloak SSO; there is no local password, registration or administrator bootstrap.
Roles are assigned in EIM and arrive in the access token's `eim_roles` claim; each endpoint lists the roles it accepts
in `@PreAuthorize("hasAnyRole(...)")`, as rsa-core-service does. The menu is the user's EIM sitemap (`GET /auth/sitemaps`).

| Role | Access |
| --- | --- |
| `WF_VIEWER` | Read process definitions and process instances |
| `WF_OPERATOR` | Viewer access plus start processes and handle tasks |
| `WF_ADMIN` | Operator access plus definitions, assignment rules, services, and agents |
| `WF_PLATFORM_ADMIN` | Full access plus tenants and tenant members; bypasses tenant row-level security |

Tenant membership stays local: it only scopes a user's data and is managed by `WF_PLATFORM_ADMIN`. A user is added to the
default tenant on first sign-in.

Service APIs use one `X-Workflow-Token` header. The token envelope is:

```text
clientCode.base64Url(aesGcm(clientSecret, payloadJson))
```

The payload is:

```json
{
  "clientCode": "local-dev",
  "tenantCode": "default",
  "timestamp": 1785484800,
  "nonce": "unique-request-nonce",
  "method": "POST",
  "path": "/workflow/process/start",
  "bodySha256": "sha256-hex-of-request-body",
  "tokenVersion": 1
}
```

Each client has an independent secret, allowed tenant codes, allowed paths, a secret version, and an optional expiration time. `X-Tenant-Code` is not accepted. BPMN XML is transported as a plain JSON string and is never Base64 encoded.

## Assignment expressions

Assignment rules are exposed to BPMN through the `assigneeService` bean:

- `${assigneeService.getAssignee(execution)}`
- `${assigneeService.getCandidates(execution)}`
- `${assigneeService.getCandidateGroups(execution)}`
- `${assigneeService.getAssigneeList(execution)}`

To apply `TO_ASSIGNEE`, `AUTO_COMPLETE`, or `AUTO_REJECT` when no handler is resolved, add a `create` task listener to the relevant user task with delegate expression `${assignmentFallbackTaskListener}`. Automatic completion and rejection run after the process transaction commits, preventing conflicts with timer boundary event persistence.

## Local Configuration

Start the local profile with PostgreSQL on `localhost:5432/workflow_agent`:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:WORKFLOW_KEYCLOAK_SERVER_URL='https://uat-auth-sso.vetc.com.vn'
$env:WORKFLOW_KEYCLOAK_CLIENT_ID='VETC.WORKFLOW'
$env:WORKFLOW_KEYCLOAK_CLIENT_SECRET='<client secret>'
mvn -pl workflow-boot -am spring-boot:run
```

Required secrets:

```text
WORKFLOW_MASTER_KEY_BASE64=<base64 encoded 256-bit key for database-encrypted client secrets>
WORKFLOW_KEYCLOAK_SERVER_URL=<Keycloak base URL; realm defaults to rsa>
WORKFLOW_KEYCLOAK_CLIENT_ID=VETC.WORKFLOW
WORKFLOW_KEYCLOAK_CLIENT_SECRET=<client secret>
```

Optional database variables:

```text
WORKFLOW_DB_URL=jdbc:postgresql://localhost:5432/workflow_agent
WORKFLOW_DB_USERNAME=postgres
WORKFLOW_DB_PASSWORD=root
```

Redis defaults to `localhost:6379`. Production configuration has no database credential defaults, disables Flyway baselining,
and disables Flowable schema auto-update. Database migrations are managed by Flyway; only the explicit `local` profile lets
Flowable update its own engine tables.

## Build

```shell
mvn test
mvn verify
```

`verify` enforces the JDK/Maven baseline, Java conventions, SpotBugs high-priority findings, and a minimum tested-code
coverage floor. ArchUnit tests prevent domain-to-framework, application-to-infrastructure, direct REST-to-domain,
and cross-bounded-context infrastructure dependencies. Docker-enabled environments additionally run PostgreSQL,
Redis, Flyway, Flowable, and HTTP security integration tests through Testcontainers.
