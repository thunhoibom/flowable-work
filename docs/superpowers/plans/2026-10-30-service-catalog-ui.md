# Service Catalog UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Dịch vụ screen as a service list plus an operation editor, without changing the catalog API.

**Architecture:** Pure functions decide publish badges, dirty state, validation, and the save payload. Three presentational Vue components render the list, the operation table, and the five-part form. `ServiceCatalogView.vue` owns selection, the create dialog, publish, and the API calls.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Vue Test Utils, existing `serviceCatalogApi`.

## Global Constraints

- Route `/services` stays. Menu label stays **Dịch vụ**. Do not edit `src/router/index.ts`.
- Do not add or change API methods. `api.ts` and `parameterDraft.ts` stay as they are.
- No delete, rename, test call, custom headers, OpenAPI import, or pagination.
- Publish status compares only `method`, `urlTemplate`, `bpmnErrorCode`, `inputParameters`, and `outputParameters` with `publishedMethod`, `publishedUrl`, `publishedBpmnErrorCode`, `publishedInputParameters`, and `publishedOutputParameters`.
- Auth, secret, body template, timeout, and HTTP error codes do not change the publish badge.
- No `publishedUrl` means **Chưa phát hành**. A difference in the compared fields means **Có thay đổi**. Otherwise **Đã phát hành**.
- Parameter equality ignores order. Inputs compare name, type, required, and location. Outputs compare name, type, and jsonField.
- A missing published method or published parameter list differs only when the draft has a value.
- `PATH` stays required and locked through the existing `pathRequirement`.
- An untouched blank new-operation form does not block publish. A changed form blocks publish and asks before leaving.
- Publish itself does not ask for confirmation.
- A service with no operations can still be published.
- Commands below run from `workflow-agent-web`.

---

### Task 1: Publish status

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/catalogStatus.ts`
- Test: `workflow-agent-web/src/features/service-registry/catalogStatus.spec.ts`

**Interfaces:**
- Consumes: `CatalogInputParameter`, `CatalogOperation`, `CatalogOutputParameter` from `./api`
- Produces:
  - `export type OperationPublishStatus = 'unpublished' | 'changed' | 'published'`
  - `export const operationStatusLabel: Record<OperationPublishStatus, string>` with `unpublished: 'Chưa phát hành'`, `changed: 'Có thay đổi'`, `published: 'Đã phát hành'`
  - `export function operationPublishStatus(operation: CatalogOperation): OperationPublishStatus`
  - `export function serviceNeedsPublish(operations: CatalogOperation[]): boolean`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { operationPublishStatus, serviceNeedsPublish } from './catalogStatus'
import type { CatalogOperation } from './api'

function operation(overrides: Partial<CatalogOperation> = {}): CatalogOperation {
  return {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'NONE',
    secretPresent: false,
    bodyTemplate: '{"sku":1}',
    timeoutMillis: 5000,
    errorStatusCodes: ['409', '5XX'],
    bpmnErrorCode: 'HTTP_ERROR',
    publishedUrl: 'https://crm.example/orders/${orderId}',
    publishedMethod: 'POST',
    publishedBpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
    outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    publishedInputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
    publishedOutputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    ...overrides,
  }
}

describe('operationPublishStatus', () => {
  it('labels a matching snapshot as published', () => {
    expect(operationPublishStatus(operation())).toBe('published')
  })

  it('labels a missing published url as unpublished', () => {
    expect(operationPublishStatus(operation({ publishedUrl: undefined }))).toBe('unpublished')
    expect(operationPublishStatus(operation({ publishedUrl: '' }))).toBe('unpublished')
  })

  it('treats reordered parameters as the same snapshot', () => {
    expect(
      operationPublishStatus(
        operation({
          inputParameters: [
            { name: 'b', type: 'STRING', required: false, location: 'QUERY' },
            { name: 'a', type: 'STRING', required: true, location: 'PATH' },
          ],
          publishedInputParameters: [
            { name: 'a', type: 'STRING', required: true, location: 'PATH' },
            { name: 'b', type: 'STRING', required: false, location: 'QUERY' },
          ],
          outputParameters: [
            { name: 'second', type: 'STRING', jsonField: 'second' },
            { name: 'id', type: 'LONG', jsonField: 'id' },
          ],
          publishedOutputParameters: [
            { name: 'id', type: 'LONG', jsonField: 'id' },
            { name: 'second', type: 'STRING', jsonField: 'second' },
          ],
        }),
      ),
    ).toBe('published')
  })

  it('labels a different method, url, error code, or parameter as changed', () => {
    expect(operationPublishStatus(operation({ method: 'PUT' }))).toBe('changed')
    expect(operationPublishStatus(operation({ urlTemplate: 'https://other.example' }))).toBe('changed')
    expect(operationPublishStatus(operation({ bpmnErrorCode: 'CRM_ERROR' }))).toBe('changed')
    expect(
      operationPublishStatus(
        operation({
          inputParameters: [{ name: 'orderId', type: 'LONG', required: true, location: 'PATH' }],
        }),
      ),
    ).toBe('changed')
  })

  it('ignores auth, secret, body, timeout, and http error codes', () => {
    expect(
      operationPublishStatus(
        operation({
          authType: 'BEARER',
          secretPresent: true,
          bodyTemplate: '{"sku":2}',
          timeoutMillis: 1000,
          errorStatusCodes: ['500'],
        }),
      ),
    ).toBe('published')
  })

  it('treats a missing published method or parameter list as changed only when the draft has a value', () => {
    expect(operationPublishStatus(operation({ publishedMethod: undefined, method: 'POST' }))).toBe(
      'changed',
    )
    expect(
      operationPublishStatus(
        operation({
          publishedMethod: undefined,
          method: '',
          publishedInputParameters: undefined,
          inputParameters: [],
          publishedOutputParameters: undefined,
          outputParameters: [],
        }),
      ),
    ).toBe('published')
    expect(
      operationPublishStatus(
        operation({
          publishedInputParameters: undefined,
          inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
        }),
      ),
    ).toBe('changed')
  })
})

describe('serviceNeedsPublish', () => {
  it('is true when any operation is not published', () => {
    expect(serviceNeedsPublish([operation(), operation({ publishedUrl: undefined })])).toBe(true)
    expect(serviceNeedsPublish([operation()])).toBe(false)
    expect(serviceNeedsPublish([])).toBe(false)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/catalogStatus.spec.ts`

Expected: FAIL because `./catalogStatus` cannot be resolved.

- [ ] **Step 3: Write the implementation**

```ts
import type { CatalogInputParameter, CatalogOperation, CatalogOutputParameter } from './api'

export type OperationPublishStatus = 'unpublished' | 'changed' | 'published'

export const operationStatusLabel: Record<OperationPublishStatus, string> = {
  unpublished: 'Chưa phát hành',
  changed: 'Có thay đổi',
  published: 'Đã phát hành',
}

export function operationPublishStatus(operation: CatalogOperation): OperationPublishStatus {
  if (!operation.publishedUrl) return 'unpublished'
  if (
    !sameText(operation.method, operation.publishedMethod) ||
    operation.urlTemplate !== operation.publishedUrl ||
    !sameText(operation.bpmnErrorCode, operation.publishedBpmnErrorCode) ||
    !sameInputs(operation.inputParameters, operation.publishedInputParameters) ||
    !sameOutputs(operation.outputParameters, operation.publishedOutputParameters)
  ) {
    return 'changed'
  }
  return 'published'
}

export function serviceNeedsPublish(operations: CatalogOperation[]): boolean {
  return operations.some((operation) => operationPublishStatus(operation) !== 'published')
}

function sameText(draft: string | null | undefined, published: string | null | undefined) {
  return (draft ?? '') === (published ?? '')
}

function sameInputs(
  draft: CatalogInputParameter[] | undefined,
  published: CatalogInputParameter[] | undefined,
) {
  const left = draft ?? []
  if (published == null) return left.length === 0
  return sameMembers(left, published, (row) =>
    [row.name, row.type, String(row.required), row.location].join('\u0000'),
  )
}

function sameOutputs(
  draft: CatalogOutputParameter[] | undefined,
  published: CatalogOutputParameter[] | undefined,
) {
  const left = draft ?? []
  if (published == null) return left.length === 0
  return sameMembers(left, published, (row) => [row.name, row.type, row.jsonField].join('\u0000'))
}

function sameMembers<T>(left: T[], right: T[], signature: (value: T) => string) {
  if (left.length !== right.length) return false
  const published = right.map(signature).sort()
  return left
    .map(signature)
    .sort()
    .every((value, index) => value === published[index])
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm test:unit:run -- src/features/service-registry/catalogStatus.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/catalogStatus.ts workflow-agent-web/src/features/service-registry/catalogStatus.spec.ts
git commit -m "feat: label service operations by publish snapshot"
```

---

### Task 2: Operation draft

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/operationDraft.ts`
- Test: `workflow-agent-web/src/features/service-registry/operationDraft.spec.ts`

**Interfaces:**
- Consumes: `CatalogOperation`, `SaveOperationCommand` from `./api`; `pathRequirement` from `./parameterDraft`
- Produces:

```ts
export interface ParameterInputRow {
  name: string
  type: string
  required: boolean
  location: string
}

export interface ParameterOutputRow {
  name: string
  type: string
  jsonField: string
}

export interface OperationDraft {
  operationKey: string
  method: string
  url: string
  authType: string
  apiKeyHeader: string
  secret: string
  bodyTemplate: string
  timeoutMillis: number
  errorStatusCodes: string
  bpmnErrorCode: string
  inputs: ParameterInputRow[]
  outputs: ParameterOutputRow[]
}

export const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const
export const authTypes = [
  { value: 'NONE', label: 'Không' },
  { value: 'BEARER', label: 'Bearer' },
  { value: 'BASIC', label: 'Basic' },
  { value: 'API_KEY', label: 'API Key' },
] as const
export const parameterTypes = ['STRING', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON'] as const
export const parameterLocations = ['PATH', 'QUERY', 'HEADER', 'BODY'] as const

export function blankOperationDraft(): OperationDraft
export function draftFromOperation(operation: CatalogOperation): OperationDraft
export function isOperationDraftDirty(current: OperationDraft, baseline: OperationDraft): boolean
export function validateOperationDraft(draft: OperationDraft): string | null
export function toSaveCommand(draft: OperationDraft): SaveOperationCommand
```

`blankOperationDraft()` returns method `POST`, auth `NONE`, timeout `5000`, error codes `409,5XX`, BPMN error `HTTP_ERROR`, and empty strings, inputs, and outputs. `draftFromOperation` copies the operation, joins error codes with commas, leaves `secret` empty, and applies `pathRequirement` to each input. Dirty comparison does not trim. Validation trims and returns the first message: `Thiếu mã thao tác`, `Thiếu mẫu địa chỉ`, `Thời gian chờ phải từ 1 mili giây`, `Thiếu header API key`, `Thiếu tên tham số đầu vào`, `Thiếu tên tham số đầu ra`, `Thiếu trường JSON đầu ra`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import type { CatalogOperation } from './api'
import {
  blankOperationDraft,
  draftFromOperation,
  isOperationDraftDirty,
  toSaveCommand,
  validateOperationDraft,
} from './operationDraft'

function operation(): CatalogOperation {
  return {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'API_KEY',
    apiKeyHeader: 'X-Api-Key',
    secretPresent: true,
    bodyTemplate: '{"sku":1}',
    timeoutMillis: 1000,
    errorStatusCodes: ['409', '5XX'],
    bpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [{ name: 'orderId', type: 'STRING', required: false, location: 'PATH' }],
    outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
  }
}

describe('operation draft', () => {
  it('treats two blank drafts as clean and an edited url as dirty', () => {
    const baseline = blankOperationDraft()
    expect(isOperationDraftDirty(blankOperationDraft(), baseline)).toBe(false)
    expect(isOperationDraftDirty({ ...baseline, url: 'https://crm.example' }, baseline)).toBe(true)
  })

  it('loads an operation without copying the secret and locks path parameters', () => {
    const draft = draftFromOperation(operation())
    expect(draft.secret).toBe('')
    expect(draft.errorStatusCodes).toBe('409,5XX')
    expect(draft.inputs[0]?.required).toBe(true)
    expect(isOperationDraftDirty(draft, draftFromOperation(operation()))).toBe(false)
  })

  it('names the first missing field', () => {
    const draft = blankOperationDraft()
    expect(validateOperationDraft(draft)).toBe('Thiếu mã thao tác')
    expect(validateOperationDraft({ ...draft, operationKey: 'createLead' })).toBe('Thiếu mẫu địa chỉ')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        timeoutMillis: 0,
      }),
    ).toBe('Thời gian chờ phải từ 1 mili giây')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        authType: 'API_KEY',
      }),
    ).toBe('Thiếu header API key')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        inputs: [{ name: ' ', type: 'STRING', required: false, location: 'QUERY' }],
      }),
    ).toBe('Thiếu tên tham số đầu vào')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        outputs: [{ name: ' ', type: 'STRING', jsonField: 'id' }],
      }),
    ).toBe('Thiếu tên tham số đầu ra')
    expect(
      validateOperationDraft({
        ...draft,
        operationKey: 'createLead',
        url: 'https://crm.example',
        outputs: [{ name: 'id', type: 'STRING', jsonField: ' ' }],
      }),
    ).toBe('Thiếu trường JSON đầu ra')
    expect(
      validateOperationDraft({ ...draft, operationKey: ' createLead ', url: ' https://crm.example ' }),
    ).toBeNull()
  })

  it('builds the existing save payload', () => {
    const draft = draftFromOperation(operation())
    draft.secret = ''
    draft.bpmnErrorCode = ' '
    draft.apiKeyHeader = ' X-Api-Key '
    expect(toSaveCommand(draft)).toEqual({
      method: 'POST',
      url: 'https://crm.example/orders/${orderId}',
      authType: 'API_KEY',
      apiKeyHeader: 'X-Api-Key',
      secret: undefined,
      bodyTemplate: '{"sku":1}',
      timeoutMillis: 1000,
      errorStatusCodes: ['409', '5XX'],
      bpmnErrorCode: 'HTTP_ERROR',
      inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
      outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    })
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/operationDraft.spec.ts`

Expected: FAIL because `./operationDraft` cannot be resolved.

- [ ] **Step 3: Write the implementation**

```ts
import type { CatalogOperation, SaveOperationCommand } from './api'
import { pathRequirement } from './parameterDraft'

export interface ParameterInputRow {
  name: string
  type: string
  required: boolean
  location: string
}

export interface ParameterOutputRow {
  name: string
  type: string
  jsonField: string
}

export interface OperationDraft {
  operationKey: string
  method: string
  url: string
  authType: string
  apiKeyHeader: string
  secret: string
  bodyTemplate: string
  timeoutMillis: number
  errorStatusCodes: string
  bpmnErrorCode: string
  inputs: ParameterInputRow[]
  outputs: ParameterOutputRow[]
}

export const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const
export const authTypes = [
  { value: 'NONE', label: 'Không' },
  { value: 'BEARER', label: 'Bearer' },
  { value: 'BASIC', label: 'Basic' },
  { value: 'API_KEY', label: 'API Key' },
] as const
export const parameterTypes = ['STRING', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON'] as const
export const parameterLocations = ['PATH', 'QUERY', 'HEADER', 'BODY'] as const

export function blankOperationDraft(): OperationDraft {
  return {
    operationKey: '',
    method: 'POST',
    url: '',
    authType: 'NONE',
    apiKeyHeader: '',
    secret: '',
    bodyTemplate: '',
    timeoutMillis: 5000,
    errorStatusCodes: '409,5XX',
    bpmnErrorCode: 'HTTP_ERROR',
    inputs: [],
    outputs: [],
  }
}

export function draftFromOperation(operation: CatalogOperation): OperationDraft {
  return {
    operationKey: operation.operationKey,
    method: operation.method,
    url: operation.urlTemplate,
    authType: operation.authType,
    apiKeyHeader: operation.apiKeyHeader || '',
    secret: '',
    bodyTemplate: operation.bodyTemplate || '',
    timeoutMillis: operation.timeoutMillis,
    errorStatusCodes: operation.errorStatusCodes.join(','),
    bpmnErrorCode: operation.bpmnErrorCode,
    inputs: (operation.inputParameters || []).map((row) => ({
      name: row.name,
      type: row.type,
      required: pathRequirement(row.location, row.required).required,
      location: row.location,
    })),
    outputs: (operation.outputParameters || []).map((row) => ({ ...row })),
  }
}

export function isOperationDraftDirty(current: OperationDraft, baseline: OperationDraft) {
  return JSON.stringify(snapshot(current)) !== JSON.stringify(snapshot(baseline))
}

export function validateOperationDraft(draft: OperationDraft): string | null {
  if (!draft.operationKey.trim()) return 'Thiếu mã thao tác'
  if (!draft.url.trim()) return 'Thiếu mẫu địa chỉ'
  if (!Number.isInteger(draft.timeoutMillis) || draft.timeoutMillis < 1) {
    return 'Thời gian chờ phải từ 1 mili giây'
  }
  if (draft.authType === 'API_KEY' && !draft.apiKeyHeader.trim()) return 'Thiếu header API key'
  if (draft.inputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu vào'
  if (draft.outputs.some((row) => !row.name.trim())) return 'Thiếu tên tham số đầu ra'
  if (draft.outputs.some((row) => !row.jsonField.trim())) return 'Thiếu trường JSON đầu ra'
  return null
}

export function toSaveCommand(draft: OperationDraft): SaveOperationCommand {
  return {
    method: draft.method,
    url: draft.url.trim(),
    authType: draft.authType,
    apiKeyHeader: draft.apiKeyHeader.trim() || undefined,
    secret: draft.secret || undefined,
    bodyTemplate: draft.bodyTemplate || undefined,
    timeoutMillis: draft.timeoutMillis,
    errorStatusCodes: draft.errorStatusCodes
      .split(',')
      .map((code) => code.trim())
      .filter(Boolean),
    bpmnErrorCode: draft.bpmnErrorCode.trim() || 'HTTP_ERROR',
    inputParameters: draft.inputs.map((row) => ({
      name: row.name.trim(),
      type: row.type,
      required: pathRequirement(row.location, row.required).required,
      location: row.location,
    })),
    outputParameters: draft.outputs.map((row) => ({
      name: row.name.trim(),
      type: row.type,
      jsonField: row.jsonField.trim(),
    })),
  }
}

function snapshot(draft: OperationDraft) {
  return {
    operationKey: draft.operationKey,
    method: draft.method,
    url: draft.url,
    authType: draft.authType,
    apiKeyHeader: draft.apiKeyHeader,
    secret: draft.secret,
    bodyTemplate: draft.bodyTemplate,
    timeoutMillis: draft.timeoutMillis,
    errorStatusCodes: draft.errorStatusCodes,
    bpmnErrorCode: draft.bpmnErrorCode,
    inputs: draft.inputs.map((row) => ({
      name: row.name,
      type: row.type,
      required: row.required,
      location: row.location,
    })),
    outputs: draft.outputs.map((row) => ({
      name: row.name,
      type: row.type,
      jsonField: row.jsonField,
    })),
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm test:unit:run -- src/features/service-registry/operationDraft.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/operationDraft.ts workflow-agent-web/src/features/service-registry/operationDraft.spec.ts
git commit -m "feat: validate and save service operation drafts"
```

---

### Task 3: Service list panel

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/ServiceListPanel.vue`
- Test: `workflow-agent-web/src/features/service-registry/ServiceListPanel.spec.ts`

**Interfaces:**
- Consumes: `serviceNeedsPublish` and `CatalogOperation` shapes through `ServiceView`
- Produces: component props `services: ServiceView[]`, `selectedKey: string`, `query: string`; emits `update:query` with the input string and `select` with `serviceKey`

The panel filters `serviceKey` and `name` with `trim().toLowerCase()`. Each row shows the name, the key, the operation count as `N thao tác`, and **Cần phát hành** when `serviceNeedsPublish` is true. The selected row sets `aria-current="true"`. An empty filter result shows **Không có dịch vụ khớp**.

- [ ] **Step 1: Write the failing test**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ServiceListPanel from './ServiceListPanel.vue'
import type { ServiceView } from './api'

const services: ServiceView[] = [
  {
    serviceKey: 'crm',
    name: 'CRM',
    operations: [
      {
        operationKey: 'createLead',
        method: 'POST',
        urlTemplate: 'https://crm.example',
        authType: 'NONE',
        secretPresent: false,
        timeoutMillis: 5000,
        errorStatusCodes: [],
        bpmnErrorCode: 'HTTP_ERROR',
        publishedUrl: 'https://crm.example',
        publishedMethod: 'POST',
        publishedBpmnErrorCode: 'HTTP_ERROR',
        inputParameters: [],
        outputParameters: [],
        publishedInputParameters: [],
        publishedOutputParameters: [],
      },
    ],
  },
  {
    serviceKey: 'billing',
    name: 'Billing',
    operations: [
      {
        operationKey: 'charge',
        method: 'POST',
        urlTemplate: 'https://billing.example',
        authType: 'NONE',
        secretPresent: false,
        timeoutMillis: 5000,
        errorStatusCodes: [],
        bpmnErrorCode: 'HTTP_ERROR',
      },
    ],
  },
]

describe('ServiceListPanel', () => {
  it('filters without case and marks a service that still needs publish', () => {
    const wrapper = mount(ServiceListPanel, {
      props: { services, selectedKey: 'crm', query: 'bill' },
    })

    expect(wrapper.text()).toContain('Billing')
    expect(wrapper.text()).toContain('Cần phát hành')
    expect(wrapper.text()).not.toContain('CRM')
  })

  it('says when nothing matches and emits the selected service', async () => {
    const wrapper = mount(ServiceListPanel, {
      props: { services, selectedKey: 'crm', query: 'zzz' },
    })

    expect(wrapper.text()).toContain('Không có dịch vụ khớp')
    await wrapper.setProps({ query: '' })
    await wrapper.get('[data-service="billing"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['billing'])
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/ServiceListPanel.spec.ts`

Expected: FAIL because `ServiceListPanel.vue` cannot be resolved.

- [ ] **Step 3: Write the implementation**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { ServiceView } from './api'
import { serviceNeedsPublish } from './catalogStatus'

const props = defineProps<{
  services: ServiceView[]
  selectedKey: string
  query: string
}>()

const emit = defineEmits<{
  'update:query': [value: string]
  select: [serviceKey: string]
}>()

const visibleServices = computed(() => {
  const needle = props.query.trim().toLowerCase()
  if (!needle) return props.services
  return props.services.filter((service) =>
    [service.serviceKey, service.name].some((value) => value.toLowerCase().includes(needle)),
  )
})
</script>

<template>
  <div class="service-list">
    <el-input
      :model-value="query"
      clearable
      placeholder="Tìm theo mã hoặc tên"
      aria-label="Tìm dịch vụ"
      @update:model-value="emit('update:query', String($event ?? ''))"
    />
    <p v-if="query.trim() && !visibleServices.length" class="service-list__empty">
      Không có dịch vụ khớp
    </p>
    <div v-else class="service-list__items" role="list">
      <button
        v-for="service in visibleServices"
        :key="service.serviceKey"
        type="button"
        role="listitem"
        :data-service="service.serviceKey"
        :aria-current="service.serviceKey === selectedKey ? 'true' : undefined"
        @click="emit('select', service.serviceKey)"
      >
        <span>
          <strong>{{ service.name }}</strong>
          <small>{{ service.serviceKey }}</small>
        </span>
        <span>
          <em>{{ service.operations.length }} thao tác</em>
          <b v-if="serviceNeedsPublish(service.operations)">Cần phát hành</b>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.service-list {
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.service-list__empty {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 13px;
}

.service-list__items {
  display: grid;
  gap: 8px;
}

.service-list__items button {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-md);
  color: var(--color-text);
  background: var(--color-surface);
  text-align: left;
  cursor: pointer;
}

.service-list__items button[aria-current='true'] {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.service-list__items span {
  display: grid;
  gap: 4px;
}

.service-list__items strong {
  font-size: 14px;
}

.service-list__items small,
.service-list__items em {
  color: var(--color-text-muted);
  font-size: 12px;
  font-style: normal;
}

.service-list__items b {
  justify-self: end;
  color: var(--color-warning);
  font-size: 12px;
  font-weight: 650;
}
</style>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm test:unit:run -- src/features/service-registry/ServiceListPanel.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/ServiceListPanel.vue workflow-agent-web/src/features/service-registry/ServiceListPanel.spec.ts
git commit -m "feat: add a searchable service catalog list"
```

---

### Task 4: Operation table

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/OperationTable.vue`
- Test: `workflow-agent-web/src/features/service-registry/OperationTable.spec.ts`

**Interfaces:**
- Consumes: `operationPublishStatus`, `operationStatusLabel`
- Produces: props `operations: CatalogOperation[]`, `selectedOperationKey: string`; emits `select` with `operationKey` and `add` with no payload

Columns are Phương thức, Mã thao tác, Mẫu địa chỉ, Trạng thái, Khóa. Secret text is **Đã lưu khóa** or **Không có khóa**. The operation button keeps `data-operation`. An empty list shows **Chưa có thao tác**.

- [ ] **Step 1: Write the failing test**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OperationTable from './OperationTable.vue'
import type { CatalogOperation } from './api'

const operations: CatalogOperation[] = [
  {
    operationKey: 'createLead',
    method: 'POST',
    urlTemplate: 'https://crm.example/orders/${orderId}',
    authType: 'NONE',
    secretPresent: true,
    timeoutMillis: 5000,
    errorStatusCodes: [],
    bpmnErrorCode: 'HTTP_ERROR',
    publishedUrl: 'https://crm.example/orders/${orderId}',
    publishedMethod: 'POST',
    publishedBpmnErrorCode: 'HTTP_ERROR',
    inputParameters: [],
    outputParameters: [],
    publishedInputParameters: [],
    publishedOutputParameters: [],
  },
]

describe('OperationTable', () => {
  it('shows the operation, its publish label, and emits select or add', async () => {
    const wrapper = mount(OperationTable, {
      props: { operations, selectedOperationKey: '' },
    })

    expect(wrapper.text()).toContain('POST')
    expect(wrapper.text()).toContain('Đã phát hành')
    expect(wrapper.text()).toContain('Đã lưu khóa')
    await wrapper.get('[data-operation="createLead"]').trigger('click')
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['createLead'])
    expect(wrapper.emitted('add')).toHaveLength(1)
  })

  it('shows an empty operation list', () => {
    const wrapper = mount(OperationTable, {
      props: { operations: [], selectedOperationKey: '' },
    })
    expect(wrapper.text()).toContain('Chưa có thao tác')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/OperationTable.spec.ts`

Expected: FAIL because `OperationTable.vue` cannot be resolved.

- [ ] **Step 3: Write the implementation**

```vue
<script setup lang="ts">
import type { CatalogOperation } from './api'
import { operationPublishStatus, operationStatusLabel } from './catalogStatus'

defineProps<{
  operations: CatalogOperation[]
  selectedOperationKey: string
}>()

const emit = defineEmits<{
  select: [operationKey: string]
  add: []
}>()
</script>

<template>
  <div class="operation-table">
    <div class="operation-table__header">
      <strong>Thao tác</strong>
      <el-button data-action="add-operation" @click="emit('add')">Thêm thao tác</el-button>
    </div>
    <p v-if="!operations.length">Chưa có thao tác</p>
    <table v-else>
      <thead>
        <tr>
          <th>Phương thức</th>
          <th>Mã thao tác</th>
          <th>Mẫu địa chỉ</th>
          <th>Trạng thái</th>
          <th>Khóa</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="operation in operations"
          :key="operation.operationKey"
          :data-status="operationPublishStatus(operation)"
          :aria-selected="operation.operationKey === selectedOperationKey"
        >
          <td>{{ operation.method }}</td>
          <td>
            <button
              type="button"
              :data-operation="operation.operationKey"
              @click="emit('select', operation.operationKey)"
            >
              {{ operation.operationKey }}
            </button>
          </td>
          <td>{{ operation.urlTemplate }}</td>
          <td>{{ operationStatusLabel[operationPublishStatus(operation)] }}</td>
          <td>{{ operation.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.operation-table {
  display: grid;
  gap: 12px;
}

.operation-table__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.operation-table p {
  margin: 0;
  color: var(--color-text-muted);
}

.operation-table table {
  width: 100%;
  border-collapse: collapse;
}

.operation-table th,
.operation-table td {
  padding: 10px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  text-align: left;
  font-size: 13px;
}

.operation-table tr[aria-selected='true'] {
  background: var(--color-primary-soft);
}

.operation-table button {
  padding: 0;
  border: 0;
  color: var(--color-primary-strong);
  background: transparent;
  font: inherit;
  cursor: pointer;
}
</style>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm test:unit:run -- src/features/service-registry/OperationTable.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/OperationTable.vue workflow-agent-web/src/features/service-registry/OperationTable.spec.ts
git commit -m "feat: show service operations in a catalog table"
```

---

### Task 5: Operation editor

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/OperationEditor.vue`
- Test: `workflow-agent-web/src/features/service-registry/OperationEditor.spec.ts`

**Interfaces:**
- Consumes: `OperationDraft`, `authTypes`, `httpMethods`, `parameterLocations`, `parameterTypes` from `./operationDraft`; `pathRequirement` from `./parameterDraft`
- Produces: `defineModel<OperationDraft>({ required: true })`; props `operationKeyLocked: boolean`, `secretPresent: boolean`, `saving: boolean`; emit `save`

The editor renders five sections: **Yêu cầu**, **Xác thực**, **Lỗi**, **Đầu vào**, **Đầu ra**. Controls use `data-field` values `operation-key`, `url`, `body-template`, `api-key-header`, and `bpmn-error-code`. The operation key input is disabled when `operationKeyLocked` is true. API key header is visible only for `API_KEY`. When `secretPresent` is true, the text is **Đã lưu khóa. Để trống để giữ khóa này.** A `PATH` input checkbox is disabled. **Lưu thao tác** emits `save`.

- [ ] **Step 1: Write the failing test**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OperationEditor from './OperationEditor.vue'
import { blankOperationDraft, draftFromOperation } from './operationDraft'
import type { CatalogOperation } from './api'

const inputStub = {
  props: ['modelValue', 'disabled', 'type', 'placeholder'],
  template:
    '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

const slot = { template: '<div><slot /></div>' }

function mountEditor(draft = blankOperationDraft(), props = {}) {
  return mount(OperationEditor, {
    props: { modelValue: draft, operationKeyLocked: false, secretPresent: false, saving: false, ...props },
    global: {
      stubs: {
        'el-form': slot,
        'el-form-item': slot,
        'el-input': inputStub,
        'el-input-number': inputStub,
        'el-select': slot,
        'el-option': slot,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          template: '<input type="checkbox" :disabled="disabled" :checked="modelValue" />',
        },
      },
    },
  })
}

describe('OperationEditor', () => {
  it('locks an existing operation key and a path parameter', () => {
    const operation: CatalogOperation = {
      operationKey: 'createLead',
      method: 'POST',
      urlTemplate: 'https://crm.example/orders/${orderId}',
      authType: 'NONE',
      secretPresent: false,
      bodyTemplate: '{"sku":1}',
      timeoutMillis: 1000,
      errorStatusCodes: ['409'],
      bpmnErrorCode: 'HTTP_ERROR',
      inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
      outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
    }
    const wrapper = mountEditor(draftFromOperation(operation), { operationKeyLocked: true })

    expect(wrapper.text()).toContain('Yêu cầu')
    expect(wrapper.text()).toContain('Xác thực')
    expect(wrapper.text()).toContain('Lỗi')
    expect(wrapper.text()).toContain('Đầu vào')
    expect(wrapper.text()).toContain('Đầu ra')
    expect((wrapper.get('[data-field="operation-key"]').element as HTMLInputElement).disabled).toBe(
      true,
    )
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
  })

  it('shows the saved-secret hint and emits save', async () => {
    const wrapper = mountEditor(blankOperationDraft(), { secretPresent: true })
    expect(wrapper.text()).toContain('Đã lưu khóa. Để trống để giữ khóa này.')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/OperationEditor.spec.ts`

Expected: FAIL because `OperationEditor.vue` cannot be resolved.

- [ ] **Step 3: Write the implementation**

```vue
<script setup lang="ts">
import { pathRequirement } from './parameterDraft'
import {
  authTypes,
  httpMethods,
  parameterLocations,
  parameterTypes,
  type OperationDraft,
} from './operationDraft'

const draft = defineModel<OperationDraft>({ required: true })

defineProps<{
  operationKeyLocked: boolean
  secretPresent: boolean
  saving: boolean
}>()

const emit = defineEmits<{ save: [] }>()

function addInput() {
  draft.value.inputs.push({ name: '', type: 'STRING', required: false, location: 'QUERY' })
}

function addOutput() {
  draft.value.outputs.push({ name: '', type: 'STRING', jsonField: '' })
}

function setLocation(index: number, location: string) {
  const row = draft.value.inputs[index]
  if (!row) return
  const next = pathRequirement(location, row.required)
  row.location = location
  row.required = next.required
}
</script>

<template>
  <div class="operation-editor">
    <section>
      <h3>Yêu cầu</h3>
      <el-form label-position="top">
        <el-form-item label="Mã thao tác">
          <el-input v-model="draft.operationKey" data-field="operation-key" :disabled="operationKeyLocked" />
        </el-form-item>
        <el-form-item label="Phương thức">
          <el-select v-model="draft.method">
            <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
          </el-select>
        </el-form-item>
        <el-form-item label="Mẫu địa chỉ">
          <el-input v-model="draft.url" data-field="url" placeholder="https://crm.example/leads/${leadId}" />
        </el-form-item>
        <el-form-item label="Mẫu nội dung">
          <el-input v-model="draft.bodyTemplate" data-field="body-template" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="Thời gian chờ (mili giây)">
          <el-input-number v-model="draft.timeoutMillis" :min="1" />
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Xác thực</h3>
      <el-form label-position="top">
        <el-form-item label="Xác thực">
          <el-select v-model="draft.authType">
            <el-option v-for="auth in authTypes" :key="auth.value" :label="auth.label" :value="auth.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft.authType === 'API_KEY'" label="Header API Key">
          <el-input v-model="draft.apiKeyHeader" data-field="api-key-header" placeholder="X-Api-Key" />
        </el-form-item>
        <el-form-item label="Khóa">
          <el-input
            v-model="draft.secret"
            type="password"
            show-password
            placeholder="Để trống để giữ khóa đã lưu"
          />
          <p v-if="secretPresent">Đã lưu khóa. Để trống để giữ khóa này.</p>
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Lỗi</h3>
      <el-form label-position="top">
        <el-form-item label="Mã trạng thái lỗi">
          <el-input v-model="draft.errorStatusCodes" placeholder="409,5XX" />
        </el-form-item>
        <el-form-item label="Mã lỗi">
          <el-input v-model="draft.bpmnErrorCode" data-field="bpmn-error-code" />
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Đầu vào</h3>
      <div v-for="(row, index) in draft.inputs" :key="`input-${index}`" class="parameter-row">
        <el-input v-model="row.name" placeholder="Tên tham số" />
        <el-select v-model="row.type">
          <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-select :model-value="row.location" @update:model-value="setLocation(index, String($event || ''))">
          <el-option v-for="location in parameterLocations" :key="location" :label="location" :value="location" />
        </el-select>
        <el-checkbox
          :model-value="row.required"
          :disabled="pathRequirement(row.location, row.required).locked"
          @update:model-value="row.required = Boolean($event)"
        >
          Bắt buộc
        </el-checkbox>
        <el-button @click="draft.inputs.splice(index, 1)">Xóa</el-button>
      </div>
      <el-button @click="addInput">Thêm đầu vào</el-button>
    </section>
    <section>
      <h3>Đầu ra</h3>
      <div v-for="(row, index) in draft.outputs" :key="`output-${index}`" class="parameter-row">
        <el-input v-model="row.name" placeholder="Tên tham số" />
        <el-select v-model="row.type">
          <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input v-model="row.jsonField" placeholder="Trường JSON" />
        <el-button @click="draft.outputs.splice(index, 1)">Xóa</el-button>
      </div>
      <el-button @click="addOutput">Thêm đầu ra</el-button>
    </section>
    <el-button data-action="save-operation" :loading="saving" @click="emit('save')">Lưu thao tác</el-button>
  </div>
</template>

<style scoped>
.operation-editor {
  display: grid;
  gap: 20px;
}

.operation-editor section {
  display: grid;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border-soft);
}

.operation-editor h3 {
  margin: 0;
  font-size: 15px;
}

.operation-editor p {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
}

.parameter-row {
  display: grid;
  grid-template-columns: minmax(140px, 1.4fr) minmax(110px, 1fr) minmax(110px, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}
</style>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm test:unit:run -- src/features/service-registry/OperationEditor.spec.ts`

Expected: PASS. If the secret test clicks the wrong button, it must click `[data-action="save-operation"]`.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/OperationEditor.vue workflow-agent-web/src/features/service-registry/OperationEditor.spec.ts
git commit -m "feat: edit a service operation in sections"
```

---

### Task 6: Catalog page

**Files:**
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts`

**Interfaces:**
- Consumes: every export from Tasks 1–5, `serviceCatalogApi`, `PageHeader`, `ListEmptyState`, `confirmAction`, `getErrorMessage`, `ElMessage`
- Produces: the `/services` page

Behavior:

- Header eyebrow **Tích hợp**, title **Dịch vụ**, description `Khai báo API bên ngoài. Trong quy trình, bạn chỉ chọn dịch vụ và thao tác.` Action **Tạo dịch vụ**.
- On load, select the first service and open a blank operation form. Keep the detail when the search hides that service.
- Create dialog requires trimmed key and name. Missing key: `Thiếu mã dịch vụ`. Missing name: `Thiếu tên dịch vụ`. Duplicate in the loaded list: `Mã dịch vụ đã tồn tại`. There is no control for renaming a service.
- Before create, select, or add replaces the current form, call `confirmAction('Thay đổi thao tác chưa được lưu. Hủy các thay đổi này?', 'Hủy thay đổi', { type: 'warning', confirmButtonText: 'Hủy thay đổi', cancelButtonText: 'Ở lại' })` when `isOperationDraftDirty` is true. Cancel leaves the form in place. A clean form does not ask.
- **Phát hành** calls `serviceCatalogApi.publish(selectedKey)` without confirmation. If the form is dirty, do not call it and show `Lưu thao tác trước khi phát hành`. Success text stays `Đã phát hành. Những lần chạy sau dùng bản này`.
- Save validates first. On failure, `ElMessage.warning` with the validation text and do not call the API. On success, clear the secret by reloading the saved operation, keep the same service and operation selected, and show `Đã lưu thao tác`.
- Workspace is `grid-template-columns: 300px minmax(0, 1fr)`. At `max-width: 959px`, use one column with the list first.
- While the first load is in progress, show **Đang tải** and do not show the empty state. Zero services after loading shows `ListEmptyState` title **Chưa có dịch vụ**, description **Tạo một dịch vụ để khai báo API bên ngoài.**, and a create button.
- API failures use `ElMessage.error(getErrorMessage(error))`.

- [ ] **Step 1: Replace the view test**

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import ServiceCatalogView from './ServiceCatalogView.vue'
import { confirmAction } from '@/utils/confirmation'
import { serviceCatalogApi } from './api'

vi.mock('./api', () => ({
  serviceCatalogApi: {
    list: vi.fn(),
    create: vi.fn(),
    saveOperation: vi.fn(),
    publish: vi.fn(),
  },
}))

vi.mock('@/utils/confirmation', () => ({
  confirmAction: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

const inputStub = {
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}
const slot = { template: '<div><slot /></div>' }

const publishedOperation = {
  operationKey: 'createLead',
  method: 'POST',
  urlTemplate: 'https://crm.example/orders/${orderId}',
  authType: 'NONE',
  secretPresent: false,
  bodyTemplate: '{"sku":1}',
  timeoutMillis: 1000,
  errorStatusCodes: ['409'],
  bpmnErrorCode: 'HTTP_ERROR',
  inputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
  outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
  publishedUrl: 'https://crm.example/orders/${orderId}',
  publishedMethod: 'POST',
  publishedBpmnErrorCode: 'HTTP_ERROR',
  publishedInputParameters: [{ name: 'orderId', type: 'STRING', required: true, location: 'PATH' }],
  publishedOutputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
}

const unpublishedOperation = {
  operationKey: 'findLead',
  method: 'GET',
  urlTemplate: 'https://crm.example/leads/${leadId}',
  authType: 'API_KEY',
  apiKeyHeader: 'X-Api-Key',
  secretPresent: true,
  timeoutMillis: 5000,
  errorStatusCodes: ['409', '5XX'],
  bpmnErrorCode: 'HTTP_ERROR',
  inputParameters: [],
  outputParameters: [],
}

function mountView() {
  return mount(ServiceCatalogView, {
    global: {
      stubs: {
        'el-form': slot,
        'el-form-item': slot,
        'el-dialog': slot,
        'el-select': slot,
        'el-option': slot,
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-input': inputStub,
        'el-input-number': inputStub,
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          template: '<input type="checkbox" :disabled="disabled" :checked="modelValue" />',
        },
      },
    },
  })
}

describe('ServiceCatalogView', () => {
  beforeEach(() => {
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([
      { serviceKey: 'crm', name: 'CRM', operations: [publishedOperation, unpublishedOperation] },
    ])
    vi.mocked(serviceCatalogApi.create).mockResolvedValue()
    vi.mocked(serviceCatalogApi.saveOperation).mockResolvedValue()
    vi.mocked(serviceCatalogApi.publish).mockResolvedValue()
    vi.mocked(confirmAction).mockReset()
    vi.mocked(confirmAction).mockResolvedValue(false)
    vi.mocked(ElMessage.warning).mockReset()
  })

  it('loads a draft operation and locks a path parameter', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('button[data-operation="createLead"]').trigger('click')

    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).disabled).toBe(true)
    expect((wrapper.get('[data-field="operation-key"]').element as HTMLInputElement).disabled).toBe(
      true,
    )
  })

  it('shows the parameter editors under the operation form', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('Đầu vào')
    expect(wrapper.text()).toContain('Thêm đầu vào')
    expect(wrapper.text()).toContain('Đầu ra')
    expect(wrapper.text()).toContain('Thêm đầu ra')
  })

  it('shows an empty catalog after loading', async () => {
    vi.mocked(serviceCatalogApi.list).mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('Chưa có dịch vụ')
  })

  it('keeps the selected service when the search matches nothing', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('input[aria-label="Tìm dịch vụ"]').setValue('zzz')
    expect(wrapper.text()).toContain('Không có dịch vụ khớp')
    expect(wrapper.text()).toContain('CRM')
  })

  it('does not save when the url or api key header is missing', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    await wrapper.get('[data-field="operation-key"]').setValue('createLead')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(serviceCatalogApi.saveOperation).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('Thiếu mẫu địa chỉ')

    vi.mocked(confirmAction).mockResolvedValueOnce(true)
    await wrapper.get('button[data-operation="findLead"]').trigger('click')
    await wrapper.get('[data-field="api-key-header"]').setValue('')
    await wrapper.get('[data-action="save-operation"]').trigger('click')
    expect(serviceCatalogApi.saveOperation).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('Thiếu header API key')
  })

  it('publishes a blank new operation and blocks a dirty form', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-action="add-operation"]').trigger('click')
    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(serviceCatalogApi.publish).toHaveBeenCalledWith('crm')

    await wrapper.get('button[data-operation="createLead"]').trigger('click')
    await wrapper.get('[data-field="url"]').setValue('https://changed.example')
    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(serviceCatalogApi.publish).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('Lưu thao tác trước khi phát hành')

    await wrapper.get('button[data-operation="findLead"]').trigger('click')
    expect(confirmAction).toHaveBeenCalled()
    expect(wrapper.get('[data-field="url"]').element.value).toBe('https://changed.example')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm test:unit:run -- src/features/service-registry/ServiceCatalogView.spec.ts`

Expected: FAIL because the current view has no search input, publish action, or empty-catalog title.

- [ ] **Step 3: Replace `ServiceCatalogView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { confirmAction } from '@/utils/confirmation'
import { serviceCatalogApi, type CatalogOperation, type ServiceView } from './api'
import OperationEditor from './OperationEditor.vue'
import OperationTable from './OperationTable.vue'
import ServiceListPanel from './ServiceListPanel.vue'
import {
  blankOperationDraft,
  draftFromOperation,
  isOperationDraftDirty,
  toSaveCommand,
  validateOperationDraft,
  type OperationDraft,
} from './operationDraft'

const services = ref<ServiceView[]>([])
const loading = ref(false)
const creating = ref(false)
const saving = ref(false)
const publishing = ref(false)
const createVisible = ref(false)
const query = ref('')
const selectedKey = ref('')
const editingKey = ref<string | null>(null)
const secretPresent = ref(false)
const draft = ref<OperationDraft>(blankOperationDraft())
const baseline = ref<OperationDraft>(blankOperationDraft())
const createForm = reactive({ serviceKey: '', name: '' })

const selectedService = computed(() =>
  services.value.find((service) => service.serviceKey === selectedKey.value),
)
const dirty = computed(() => isOperationDraftDirty(draft.value, baseline.value))

function loadNew() {
  editingKey.value = null
  secretPresent.value = false
  draft.value = blankOperationDraft()
  baseline.value = blankOperationDraft()
}

function loadOperation(operation: CatalogOperation) {
  editingKey.value = operation.operationKey
  secretPresent.value = operation.secretPresent
  draft.value = draftFromOperation(operation)
  baseline.value = draftFromOperation(operation)
}

async function confirmDiscard() {
  if (!dirty.value) return true
  return confirmAction(
    'Thay đổi thao tác chưa được lưu. Hủy các thay đổi này?',
    'Hủy thay đổi',
    { type: 'warning', confirmButtonText: 'Hủy thay đổi', cancelButtonText: 'Ở lại' },
  )
}

async function refresh(prefer?: { serviceKey?: string; operationKey?: string }) {
  loading.value = true
  try {
    services.value = await serviceCatalogApi.list()
    const service =
      services.value.find((item) => item.serviceKey === (prefer?.serviceKey || selectedKey.value)) ||
      services.value[0]
    if (!service) {
      selectedKey.value = ''
      loadNew()
      return
    }
    selectedKey.value = service.serviceKey
    const operation = service.operations.find((item) => item.operationKey === prefer?.operationKey)
    if (operation) loadOperation(operation)
    else if (prefer?.operationKey === undefined && editingKey.value) {
      const current = service.operations.find((item) => item.operationKey === editingKey.value)
      if (current) loadOperation(current)
      else loadNew()
    } else loadNew()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function selectService(serviceKey: string) {
  if (serviceKey === selectedKey.value) return
  if (!(await confirmDiscard())) return
  selectedKey.value = serviceKey
  loadNew()
}

async function selectOperation(operationKey: string) {
  if (operationKey === editingKey.value) return
  if (!(await confirmDiscard())) return
  const operation = selectedService.value?.operations.find((item) => item.operationKey === operationKey)
  if (operation) loadOperation(operation)
}

async function addOperation() {
  if (editingKey.value === null && !dirty.value) return
  if (!(await confirmDiscard())) return
  loadNew()
}

function openCreate() {
  createForm.serviceKey = ''
  createForm.name = ''
  createVisible.value = true
}

async function createService() {
  const serviceKey = createForm.serviceKey.trim()
  const name = createForm.name.trim()
  if (!serviceKey) {
    ElMessage.warning('Thiếu mã dịch vụ')
    return
  }
  if (!name) {
    ElMessage.warning('Thiếu tên dịch vụ')
    return
  }
  if (services.value.some((service) => service.serviceKey === serviceKey)) {
    ElMessage.warning('Mã dịch vụ đã tồn tại')
    return
  }
  if (!(await confirmDiscard())) return
  creating.value = true
  try {
    await serviceCatalogApi.create({ serviceKey, name })
    createVisible.value = false
    await refresh({ serviceKey })
    loadNew()
    ElMessage.success('Đã lưu dịch vụ')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    creating.value = false
  }
}

async function saveOperation() {
  const message = validateOperationDraft(draft.value)
  if (message) {
    ElMessage.warning(message)
    return
  }
  const serviceKey = selectedKey.value
  const operationKey = draft.value.operationKey.trim()
  saving.value = true
  try {
    await serviceCatalogApi.saveOperation(serviceKey, operationKey, toSaveCommand(draft.value))
    await refresh({ serviceKey, operationKey })
    ElMessage.success('Đã lưu thao tác')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publishService() {
  if (!selectedKey.value) return
  if (dirty.value) {
    ElMessage.warning('Lưu thao tác trước khi phát hành')
    return
  }
  publishing.value = true
  try {
    await serviceCatalogApi.publish(selectedKey.value)
    await refresh({ serviceKey: selectedKey.value, operationKey: editingKey.value || undefined })
    ElMessage.success('Đã phát hành. Những lần chạy sau dùng bản này')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishing.value = false
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page-stack service-catalog">
    <PageHeader
      eyebrow="Tích hợp"
      title="Dịch vụ"
      description="Khai báo API bên ngoài. Trong quy trình, bạn chỉ chọn dịch vụ và thao tác."
    >
      <template #actions>
        <el-button @click="openCreate">Tạo dịch vụ</el-button>
      </template>
    </PageHeader>

    <p v-if="loading && !services.length">Đang tải</p>
    <ListEmptyState
      v-else-if="!services.length"
      title="Chưa có dịch vụ"
      description="Tạo một dịch vụ để khai báo API bên ngoài."
    >
      <el-button type="primary" @click="openCreate">Tạo dịch vụ</el-button>
    </ListEmptyState>
    <div v-else class="catalog-workspace">
      <ServiceListPanel v-model:query="query" :services="services" :selected-key="selectedKey" @select="selectService" />
      <section v-if="selectedService" class="catalog-detail">
        <header>
          <div>
            <h3>{{ selectedService.name }}</h3>
            <p>{{ selectedService.serviceKey }}</p>
          </div>
          <el-button data-action="publish" type="primary" :loading="publishing" @click="publishService">
            Phát hành
          </el-button>
        </header>
        <OperationTable
          :operations="selectedService.operations"
          :selected-operation-key="editingKey || ''"
          @select="selectOperation"
          @add="addOperation"
        />
        <OperationEditor
          v-model="draft"
          :operation-key-locked="editingKey !== null"
          :secret-present="secretPresent"
          :saving="saving"
          @save="saveOperation"
        />
      </section>
    </div>

    <el-dialog v-if="createVisible" v-model="createVisible" title="Tạo dịch vụ" width="480px">
      <el-form label-position="top">
        <el-form-item label="Mã dịch vụ">
          <el-input v-model="createForm.serviceKey" data-field="service-key" placeholder="crm" />
        </el-form-item>
        <el-form-item label="Tên dịch vụ">
          <el-input v-model="createForm.name" data-field="service-name" placeholder="CRM" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="creating" @click="createService">Lưu dịch vụ</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.catalog-workspace {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
}

.catalog-detail {
  display: grid;
  align-content: start;
  gap: 20px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.catalog-detail header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.catalog-detail h3,
.catalog-detail p {
  margin: 0;
}

.catalog-detail header p {
  color: var(--color-text-muted);
  font-size: 13px;
}

@media (max-width: 959px) {
  .catalog-workspace {
    grid-template-columns: 1fr;
  }
}
</style>
```

The search input lives inside `ServiceListPanel`. Give that input `aria-label="Tìm dịch vụ"` so the page test can find it. The panel in Task 3 already does.

`addOperation` on the initial blank form returns before `confirmDiscard`. The publish test clicks **Thêm thao tác** first; that click is a no-op and publish still runs. The dirty test then selects `createLead`. Because the form is clean, `confirmDiscard` is not called. After the URL changes, selecting `findLead` calls `confirmAction`. The mock returns false, so the changed URL stays.

- [ ] **Step 4: Run the view test**

Run: `pnpm test:unit:run -- src/features/service-registry/ServiceCatalogView.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts
git commit -m "feat: browse and edit the service catalog from one page"
```

---

### Task 7: Verify the catalog screen

**Files:**
- Modify: none expected

**Interfaces:**
- Consumes: the files from Tasks 1–6
- Produces: a passing service-registry unit run and a passing Vue type check

- [ ] **Step 1: Run the feature tests**

Run: `pnpm test:unit:run -- src/features/service-registry`

Expected: PASS for `catalogStatus`, `operationDraft`, `ServiceListPanel`, `OperationTable`, `OperationEditor`, `ServiceCatalogView`, and the existing service-registry specs.

- [ ] **Step 2: Run the type check**

Run: `pnpm type-check`

Expected: PASS

- [ ] **Step 3: Do not create an empty commit**

If Steps 1 and 2 pass without file changes, leave the git history at the Task 6 commit.
