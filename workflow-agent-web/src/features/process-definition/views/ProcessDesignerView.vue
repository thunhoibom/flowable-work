<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  shallowRef,
  triggerRef,
  watch,
} from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Copy, Download, FileUp, MoreHorizontal, Plus, Save, Trash2 } from '@lucide/vue'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import { getErrorMessage } from '@/api/http'
import { agentsEnabled } from '@/config/product'
import flowableModdle from '@/bpmn/flowableModdle'
import workflowModdle from '@/bpmn/workflowModdle'
import workflowPaletteModule from '@/bpmn/workflowPaletteModule'
import StatusBadge from '@/components/StatusBadge.vue'
import AgentTaskConfigPanel from '../components/AgentTaskConfigPanel.vue'
import ServiceCallConfigPanel from '@/features/service-registry/ServiceCallConfigPanel.vue'
import { serviceCatalogApi, type ServiceView } from '@/features/service-registry'
import {
  buildInputRows,
  buildOutputRows,
  missingRequired,
  normalizeOutputVariable,
  type ServiceInputRow,
  type ServiceOutputRow,
} from '@/features/service-registry/serviceCallMapping'
import { showsApprovalMode } from '../serviceTaskPanel'
import type {
  ApprovalMode,
  BpmnElement,
  BpmnModelerInstance,
  BpmnReplace,
  Canvas,
  ElementRegistry,
  ListenerKind,
  Moddle,
  ModdleElement,
  Modeling,
} from '@/bpmn/modeler-types'
import { assignmentRuleApi } from '@/features/assignment-rule'
import { agentApi, type PublishedAgentVersion } from '@/features/agent'
import { createBlankBpmn, isAgentTaskElement, validateBpmnXml, withoutMisplacedAgentTasks } from '@/utils/bpmn'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime, formatVersion } from '@/utils/format'
import { definitionApi } from '../api'
import { normalizeProcessIdentity } from '../domain'
import type { ActiveProcessVersion, ProcessDefinition } from '../types'

const DEFAULT_ASSIGNEE = '${assigneeService.getAssignee(execution)}'
const DEFAULT_CANDIDATE_USERS = '${assigneeService.getCandidates(execution)}'
const DEFAULT_COUNTERSIGN_COLLECTION = '${assigneeService.getAssigneeList(execution)}'
const HIDDEN_TECHNICAL_ATTRIBUTES = new Set([
  'flowable:assignee',
  'flowable:candidateUsers',
  'flowable:candidateGroups',
  'flowable:collection',
  'flowable:elementVariable',
])
const LISTENER_SECTIONS: Array<{ kind: ListenerKind; label: string; taskOnly?: boolean }> = [
  { kind: 'executionStart', label: 'Khi bắt đầu' },
  { kind: 'executionEnd', label: 'Khi kết thúc' },
  { kind: 'taskCreate', label: 'Khi tạo nhiệm vụ', taskOnly: true },
  { kind: 'taskComplete', label: 'Khi hoàn thành nhiệm vụ', taskOnly: true },
]

const route = useRoute()
const router = useRouter()
const canvasElement = ref<HTMLDivElement>()
const fileInput = ref<HTMLInputElement>()
const modeler = shallowRef<BpmnModelerInstance>()
let canvasResizeObserver: ResizeObserver | undefined
const versions = ref<ProcessDefinition[]>([])
const activeVersion = ref<ActiveProcessVersion>()
const selectedVersion = ref<number>()
const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const inheriting = ref(false)
const dirty = ref(false)
const importing = ref(false)
const newProcessVisible = ref(false)
const newProcessSaving = ref(false)
const newProcessForm = reactive({ key: '', name: '' })
const processForm = reactive({ key: '', name: '' })
const selectedElement = shallowRef<BpmnElement>()
const elementBeingEdited = shallowRef<BpmnElement>()
let elementEditSequence = 0
const selectedBusinessObject = shallowRef<ModdleElement>()
const elementForm = reactive({ id: '', name: '', documentation: '', conditionExpression: '' })
const approvalMode = ref<ApprovalMode>('')
const agentVersionId = ref('')
const serviceCallKey = ref('')
const serviceOperationKey = ref('')
const serviceInputRows = ref<ServiceInputRow[]>([])
const serviceOutputRows = ref<ServiceOutputRow[]>([])
const publishedServices = ref<ServiceView[]>([])
let serviceLoadSequence = 0
const agentInputMapping = ref('{}')
const agentOutputMapping = ref('{}')
const agentMappingRows = ref<Array<{ field: string; source: string }>>([])
const agentOutputMappingRows = ref<Array<{ field: string; target: string }>>([])
const agentProcessFailurePolicy = ref<'CONTINUE_EMPTY' | 'MANUAL_REVIEW' | 'HOLD_FOR_OPERATIONS'>(
  'HOLD_FOR_OPERATIONS',
)
const agentProcessWaitTimeoutSeconds = ref(300)
const agentVersions = ref<PublishedAgentVersion[]>([])
const agentVersionSearchLoading = ref(false)
let agentVersionSearchSequence = 0
const listenerForm = reactive({ kind: 'executionStart' as ListenerKind, bean: '' })
const listeners = reactive<Record<ListenerKind, string[]>>({
  executionStart: [],
  executionEnd: [],
  taskCreate: [],
  taskComplete: [],
})

const selectedType = computed(
  () => selectedBusinessObject.value?.$type || selectedElement.value?.type || '',
)
const isAgentTask = computed(() => isAgentTaskElement(selectedBusinessObject.value))
const isServiceTask = computed(() => selectedType.value === 'bpmn:ServiceTask')
const isTask = computed(() =>
  ['bpmn:Task', 'bpmn:UserTask', 'bpmn:ReceiveTask', 'bpmn:ServiceTask'].includes(
    selectedType.value,
  ),
)
const isSequenceFlow = computed(() => selectedType.value === 'bpmn:SequenceFlow')
const isSelectedActive = computed(() => activeVersion.value?.version === selectedVersion.value)
const selectedDefinition = computed(() =>
  versions.value.find((item) => item.version === selectedVersion.value),
)
const elementTypeLabel = computed(() => getElementTypeLabel(selectedType.value))
const selectedSource = computed(() => selectedBusinessObject.value?.sourceRef?.id || '')
const selectedTarget = computed(() => selectedBusinessObject.value?.targetRef?.id || '')
const selectedAgentVersion = computed(() =>
  agentVersions.value.find((version) => String(version.id) === agentVersionId.value),
)
const agentInputSchemaFields = computed(() =>
  schemaFields(selectedAgentVersion.value?.inputSchema).filter(
    (field) => field.mapping === 'value' || field.mapping === 'array',
  ),
)
const agentOutputSchemaFields = computed(() =>
  schemaFields(selectedAgentVersion.value?.outputSchema),
)
const extensionAttributes = computed(() => {
  const object = selectedBusinessObject.value
  if (!object) return []
  const attributes: Array<{ key: string; value: string }> = []
  Object.entries(object.$attrs || {}).forEach(([key, value]) => {
    if (
      !HIDDEN_TECHNICAL_ATTRIBUTES.has(key) &&
      value !== undefined &&
      value !== null &&
      String(value)
    ) {
      attributes.push({ key, value: String(value) })
    }
  })
  return attributes
})
const availableListenerSections = computed(() =>
  LISTENER_SECTIONS.filter((section) => !section.taskOnly || isTask.value),
)
const listenerRows = computed(() =>
  availableListenerSections.value.flatMap((section) =>
    listeners[section.kind].map((listener, index) => ({
      kind: section.kind,
      label: section.label,
      listener,
      index,
    })),
  ),
)

function service<T>(name: string) {
  return modeler.value?.get(name) as T | undefined
}

function getElementTypeLabel(type: string) {
  const labels: Record<string, string> = {
    'bpmn:StartEvent': 'Bắt đầu',
    'bpmn:EndEvent': 'Kết thúc',
    'bpmn:UserTask': 'Nhiệm vụ',
    'bpmn:Task': 'Việc thường',
    'bpmn:ServiceTask': 'Gọi dịch vụ',
    'bpmn:ExclusiveGateway': 'Rẽ một nhánh',
    'bpmn:ParallelGateway': 'Chạy song song',
    'bpmn:InclusiveGateway': 'Rẽ nhiều nhánh',
    'bpmn:SequenceFlow': 'Đường đi',
    'bpmn:SubProcess': 'Quy trình con',
  }
  return labels[type] || type || '-'
}

function readExpression(value?: ModdleElement | string) {
  if (!value) return ''
  return typeof value === 'string' ? value : value.body || value.text || value.expression || ''
}

function normalizeListener(value: string) {
  const trimmed = value.trim()
  const match = trimmed.match(/^\$\{\s*(.*?)\s*\}$/)
  return match?.[1]?.trim() || trimmed
}

function resetSelection(element?: BpmnElement) {
  selectedElement.value = element
  const object = element?.businessObject
  selectedBusinessObject.value = object
  Object.assign(elementForm, {
    id: object?.id || element?.id || '',
    name: object?.name || '',
    documentation:
      object?.documentation?.map((item) => item.text || item.body || '').join('\n') || '',
    conditionExpression: readExpression(object?.conditionExpression),
  })
  approvalMode.value = object?.loopCharacteristics
    ? 'parallel'
    : object?.candidateUsers || object?.candidateGroups
      ? 'candidate'
      : object?.assignee
        ? 'single'
        : ''
  const agentBinding = object?.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:AgentTask',
  )
  const serviceBinding = object?.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:ServiceCall',
  )
  serviceCallKey.value = String(serviceBinding?.serviceKey || '')
  serviceOperationKey.value = String(serviceBinding?.operationKey || '')
  if (object?.$type === 'bpmn:ServiceTask') {
    void loadPublishedServices(
      readSavedInputs(serviceBinding?.inputMapping),
      readSavedOutputs(serviceBinding?.outputMapping),
    )
  } else {
    serviceLoadSequence += 1
    serviceInputRows.value = []
    serviceOutputRows.value = []
  }
  agentVersionId.value = String(agentBinding?.agentVersionId || '')
  void ensureSelectedAgentVersionLoaded()
  agentInputMapping.value = String(agentBinding?.inputMapping || '{}')
  agentOutputMapping.value = String(agentBinding?.outputMapping || '{}')
  agentMappingRows.value = parseAgentMapping(agentInputMapping.value)
  agentOutputMappingRows.value = parseAgentOutputMapping(agentOutputMapping.value)
  const rawFailurePolicy = String(
    agentBinding?.processFailurePolicy || agentBinding?.failurePolicy || 'HOLD_FOR_OPERATIONS',
  )
  agentProcessFailurePolicy.value =
    rawFailurePolicy === 'CONTINUE_EMPTY' ? rawFailurePolicy : 'HOLD_FOR_OPERATIONS'
  agentProcessWaitTimeoutSeconds.value = Number(
    agentBinding?.processWaitTimeoutSeconds || agentBinding?.timeoutSeconds || 300,
  )
  Object.keys(listeners).forEach((key) => (listeners[key as ListenerKind] = []))
  for (const item of object?.extensionElements?.values || []) {
    const value = normalizeListener(
      String(item.delegateExpression || item.expression || item.class || ''),
    )
    if (!value) continue
    if (item.$type === 'flowable:ExecutionListener' && item.event === 'start')
      listeners.executionStart.push(value)
    if (item.$type === 'flowable:ExecutionListener' && item.event === 'end')
      listeners.executionEnd.push(value)
    if (item.$type === 'flowable:TaskListener' && item.event === 'create')
      listeners.taskCreate.push(value)
    if (item.$type === 'flowable:TaskListener' && item.event === 'complete')
      listeners.taskComplete.push(value)
  }
  triggerRef(selectedBusinessObject)
}

async function loadPublishedServices(
  savedInputs: Array<{ name: string; expression: string }>,
  savedOutputs: Array<{ name: string; variable: string }>,
) {
  const sequence = ++serviceLoadSequence
  const serviceKey = serviceCallKey.value
  const operationKey = serviceOperationKey.value
  try {
    const services = await serviceCatalogApi.list()
    if (sequence !== serviceLoadSequence) return
    publishedServices.value = services
  } catch (error) {
    if (sequence !== serviceLoadSequence) return
    publishedServices.value = []
    ElMessage.error(getErrorMessage(error))
  }
  if (sequence !== serviceLoadSequence) return
  const unchanged =
    serviceCallKey.value === serviceKey && serviceOperationKey.value === operationKey
  const parameters = selectedPublishedParameters()
  serviceInputRows.value = buildInputRows(
    parameters.inputs,
    unchanged ? savedInputs : serviceInputRows.value,
  )
  serviceOutputRows.value = buildOutputRows(
    parameters.outputs,
    unchanged ? savedOutputs : serviceOutputRows.value,
  )
}

function selectedPublishedParameters() {
  const operation = publishedServices.value
    .find((service) => service.serviceKey === serviceCallKey.value)
    ?.operations.find((item) => item.operationKey === serviceOperationKey.value)
  return {
    inputs: operation?.publishedInputParameters ?? [],
    outputs: operation?.publishedOutputParameters ?? [],
  }
}

function readSavedInputs(raw: unknown) {
  return readSavedEntries(raw).flatMap((item) => {
    if (typeof item.name !== 'string' || !('expression' in item)) return []
    return [{ name: item.name, expression: item.expression == null ? '' : String(item.expression) }]
  })
}

function readSavedOutputs(raw: unknown) {
  return readSavedEntries(raw).flatMap((item) => {
    if (typeof item.name !== 'string' || !('variable' in item)) return []
    return [{ name: item.name, variable: item.variable == null ? '' : String(item.variable) }]
  })
}

function readSavedEntries(raw: unknown) {
  if (typeof raw !== 'string' || raw.trim() === '') return []
  try {
    const value = JSON.parse(raw) as unknown
    if (!Array.isArray(value)) return []
    return value.filter(
      (item): item is { name?: unknown; expression?: unknown; variable?: unknown } =>
        Boolean(item) && typeof item === 'object',
    )
  } catch {
    return []
  }
}

function onServiceKey(value: string) {
  serviceCallKey.value = value
  serviceOperationKey.value = ''
  serviceInputRows.value = []
  serviceOutputRows.value = []
}

function onOperationKey(value: string) {
  const previousInputs = serviceInputRows.value
  const previousOutputs = serviceOutputRows.value
  serviceOperationKey.value = value
  const parameters = selectedPublishedParameters()
  serviceInputRows.value = buildInputRows(parameters.inputs, previousInputs)
  serviceOutputRows.value = buildOutputRows(parameters.outputs, previousOutputs)
}

function applyServiceCall() {
  const element = selectedElement.value
  const object = selectedBusinessObject.value
  const modeling = service<Modeling>('modeling')
  const moddle = service<Moddle>('moddle')
  if (!element || !object || object.$type !== 'bpmn:ServiceTask' || !modeling || !moddle) return
  const binding = object.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:ServiceCall',
  )
  const missing = missingRequired(serviceInputRows.value)
  if (missing.length > 0) {
    ElMessage.warning(`Tham số bắt buộc chưa có biểu thức: ${missing.join(', ')}`)
  }
  let repairedVariable = false
  const outputs = serviceOutputRows.value.map((row) => {
    const variable = normalizeOutputVariable(row.variable, row.variable, row.name)
    if (variable !== row.variable) repairedVariable = true
    return { ...row, variable }
  })
  if (repairedVariable) {
    ElMessage.warning('Tên biến không hợp lệ')
    serviceOutputRows.value = outputs
  }
  const values = {
    serviceKey: serviceCallKey.value,
    operationKey: serviceOperationKey.value,
    inputMapping: JSON.stringify(
      serviceInputRows.value.map((row) => ({ name: row.name, expression: row.expression })),
    ),
    outputMapping: JSON.stringify(
      outputs.map((row) => ({ name: row.name, variable: row.variable })),
    ),
  }
  if (binding) {
    modeling.updateModdleProperties(element, binding, values)
  } else {
    const extensionElements = moddle.create('bpmn:ExtensionElements', {
      values: [moddle.create('workflow:ServiceCall', values)],
    })
    modeling.updateModdleProperties(element, object, { extensionElements })
  }
  modeling.updateModdleProperties(element, object, {
    delegateExpression: '${serviceRegistryDelegate}',
  })
  dirty.value = true
}

function applyAgentVersion() {
  const element = selectedElement.value
  const object = selectedBusinessObject.value
  const modeling = service<Modeling>('modeling')
  const binding = object?.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:AgentTask',
  )
  if (!element || !object || !binding || !modeling) return
  const version = agentVersionId.value.trim()
  if (!/^\d+$/.test(version) || Number(version) <= 0) {
    ElMessage.warning('Hãy chọn phiên bản Agent đã phát hành')
    return
  }
  const selected = selectedAgentVersion.value
  if (selected && agentProcessWaitTimeoutSeconds.value > selected.timeoutSeconds) {
    agentProcessWaitTimeoutSeconds.value = selected.timeoutSeconds
  }
  modeling.updateModdleProperties(element, binding, {
    agentVersionId: version,
    inputMapping: agentInputMapping.value.trim() || '{}',
    outputMapping: agentOutputMapping.value.trim() || '{}',
    processFailurePolicy: agentProcessFailurePolicy.value,
    processWaitTimeoutSeconds: String(agentProcessWaitTimeoutSeconds.value),
  })
  dirty.value = true
}

function applyAgentMappings() {
  const element = selectedElement.value
  const object = selectedBusinessObject.value
  const modeling = service<Modeling>('modeling')
  const binding = object?.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:AgentTask',
  )
  if (!element || !object || !binding || !modeling) return
  try {
    const input = agentMappingRows.value.reduce<Record<string, string>>((result, row) => {
      if (row.field.trim() && row.source.trim()) result[row.field.trim()] = row.source.trim()
      return result
    }, {})
    const output = agentOutputMappingRows.value.reduce<Record<string, string>>((result, row) => {
      if (row.field.trim() && row.target.trim()) result[row.field.trim()] = row.target.trim()
      return result
    }, {})
    if (!input || Array.isArray(input) || typeof input !== 'object') throw new Error()
    if (!output || Array.isArray(output) || typeof output !== 'object') throw new Error()
    modeling.updateModdleProperties(element, binding, {
      inputMapping: JSON.stringify(input),
      outputMapping: JSON.stringify(output),
    })
    agentInputMapping.value = JSON.stringify(input)
    agentOutputMapping.value = JSON.stringify(output)
    dirty.value = true
  } catch {
    ElMessage.warning('Ánh xạ đầu vào và đầu ra phải là đối tượng JSON')
  }
}

function applyAgentProcessPolicy() {
  const element = selectedElement.value
  const object = selectedBusinessObject.value
  const modeling = service<Modeling>('modeling')
  const binding = object?.extensionElements?.values?.find(
    (item) => item.$type === 'workflow:AgentTask',
  )
  if (!element || !binding || !modeling) return
  const versionTimeout = selectedAgentVersion.value?.timeoutSeconds || 3600
  if (
    agentProcessWaitTimeoutSeconds.value < 1 ||
    agentProcessWaitTimeoutSeconds.value > versionTimeout
  ) {
    ElMessage.warning(`Thời hạn chờ của quy trình phải từ 1 đến ${versionTimeout} giây`)
    return
  }
  modeling.updateModdleProperties(element, binding, {
    processFailurePolicy: agentProcessFailurePolicy.value,
    processWaitTimeoutSeconds: String(agentProcessWaitTimeoutSeconds.value),
  })
  dirty.value = true
}

function parseAgentMapping(value: string) {
  try {
    const mapping = JSON.parse(value || '{}')
    return Object.entries(mapping)
      .filter(([, source]) => typeof source === 'string')
      .map(([field, source]) => ({ field, source: source as string }))
  } catch {
    return []
  }
}

function parseAgentOutputMapping(value: string) {
  try {
    const mapping = JSON.parse(value || '{}')
    return Object.entries(mapping)
      .filter(([, target]) => typeof target === 'string')
      .map(([field, target]) => ({ field, target: target as string }))
  } catch {
    return []
  }
}

function addAgentMappingRow() {
  agentMappingRows.value.push({ field: '', source: '' })
}

function removeAgentMappingRow(index: number) {
  agentMappingRows.value.splice(index, 1)
  applyAgentMappings()
}

function addAgentOutputMappingRow() {
  agentOutputMappingRows.value.push({ field: '', target: '' })
}

function removeAgentOutputMappingRow(index: number) {
  agentOutputMappingRows.value.splice(index, 1)
  applyAgentMappings()
}

async function loadAgentVersions() {
  await searchAgentVersions('')
}

async function searchAgentVersions(keyword: string) {
  const sequence = ++agentVersionSearchSequence
  agentVersionSearchLoading.value = true
  try {
    const page = await agentApi.publishedVersions({
      pageNum: 1,
      pageSize: 30,
      keyword: keyword.trim() || undefined,
    })
    if (sequence === agentVersionSearchSequence) agentVersions.value = page.records
  } catch {
    if (sequence === agentVersionSearchSequence) agentVersions.value = []
  } finally {
    if (sequence === agentVersionSearchSequence) agentVersionSearchLoading.value = false
  }
}

async function ensureSelectedAgentVersionLoaded() {
  const versionId = Number(agentVersionId.value)
  if (!versionId || agentVersions.value.some((version) => version.id === versionId)) return
  try {
    const page = await agentApi.publishedVersions({ pageNum: 1, pageSize: 1, versionId })
    const version = page.records[0]
    if (version && !agentVersions.value.some((item) => item.id === version.id)) {
      agentVersions.value = [version, ...agentVersions.value]
    }
  } catch {
    // Deployment validation remains authoritative if an imported version is unavailable.
  }
}

type SchemaPathOption = {
  path: string
  type: string
  mapping: 'value' | 'array' | 'index' | 'projection'
}

function schemaFields(schemaJson?: string) {
  if (!schemaJson) return []
  try {
    const root = JSON.parse(schemaJson) as Record<string, unknown>
    const fields: SchemaPathOption[] = []
    collectSchemaFields(root, '', fields)
    return fields
  } catch {
    return []
  }
}

function collectSchemaFields(
  schema: Record<string, unknown>,
  prefix: string,
  result: SchemaPathOption[],
  forcedMapping?: SchemaPathOption['mapping'],
) {
  const type = schemaType(schema)
  if (prefix) {
    result.push({
      path: prefix,
      type,
      mapping: forcedMapping || (type === 'array' ? 'array' : 'value'),
    })
  }
  if (type === 'array') {
    const items = schema.items
    if (!items || typeof items !== 'object') return
    const itemSchema = items as Record<string, unknown>
    result.push({ path: `${prefix}.0`, type: schemaType(itemSchema), mapping: 'index' })
    collectSchemaFields(itemSchema, `${prefix}.*`, result, 'projection')
    return
  }
  if (type !== 'object' || !schema.properties || typeof schema.properties !== 'object') return
  for (const [name, child] of Object.entries(schema.properties as Record<string, unknown>)) {
    if (child && typeof child === 'object') {
      collectSchemaFields(
        child as Record<string, unknown>,
        prefix ? `${prefix}.${name}` : name,
        result,
        forcedMapping,
      )
    }
  }
}

function schemaType(schema: Record<string, unknown>) {
  return typeof schema.type === 'string' ? schema.type : 'unknown'
}

function schemaFieldLabel(field: SchemaPathOption) {
  const mappingLabels = {
    value: 'Trường',
    array: 'Cả mảng',
    index: 'Mục đầu làm ví dụ',
    projection: 'Phép chiếu mảng',
  }
  return `${field.path} · ${field.type} · ${mappingLabels[field.mapping]}`
}

function ensureAgentTaskBinding(element?: BpmnElement) {
  const object = element?.businessObject
  const modeling = service<Modeling>('modeling')
  const moddle = service<Moddle>('moddle')
  if (!element || !object || object.$type !== 'bpmn:ReceiveTask' || !modeling || !moddle)
    return false
  const startListener = () =>
    moddle.create('flowable:ExecutionListener', {
      event: 'start',
      delegateExpression: '${agentTaskExecutionListener}',
    })
  let changed = false
  if (!isAgentTaskElement(object)) {
    const extensionElements = moddle.create('bpmn:ExtensionElements', {
      values: [
        moddle.create('workflow:AgentTask', {
          agentVersionId: '',
          inputMapping: '{}',
          outputMapping: '{}',
          processFailurePolicy: 'HOLD_FOR_OPERATIONS',
          processWaitTimeoutSeconds: '300',
        }),
        startListener(),
      ],
    })
    modeling.updateModdleProperties(element, object, { extensionElements })
    changed = true
  } else if (object.extensionElements) {
    const values = object.extensionElements.values || []
    const hasStartListener = values.some(
      (item) =>
        item.$type === 'flowable:ExecutionListener' &&
        item.event === 'start' &&
        item.delegateExpression === '${agentTaskExecutionListener}',
    )
    if (!hasStartListener) {
      modeling.updateModdleProperties(element, object.extensionElements, {
        values: [...values, startListener()],
      })
      changed = true
    }
  }
  return changed
}

function stripMisplacedAgentBindings() {
  const modeling = service<Modeling>('modeling')
  if (!modeling) return
  service<ElementRegistry>('elementRegistry')
    ?.getAll()
    .forEach((element) => {
      const object = element.businessObject
      const extensions = object?.extensionElements
      const values = extensions?.values
      if (!object || !extensions || object.$type === 'bpmn:ReceiveTask' || !values?.length) return
      const kept = values.filter(
        (item) =>
          item.$type !== 'workflow:AgentTask' &&
          item.delegateExpression !== '${agentTaskExecutionListener}',
      )
      if (kept.length === values.length) return
      if (kept.length === 0) {
        modeling.updateModdleProperties(element, object, { extensionElements: null })
      } else {
        modeling.updateModdleProperties(element, extensions, { values: kept })
      }
    })
}

function normalizeAgentTaskBindings() {
  let changed = false
  service<ElementRegistry>('elementRegistry')
    ?.getAll()
    .forEach((element) => {
      if (isAgentTaskElement(element.businessObject)) {
        changed = ensureAgentTaskBinding(element) || changed
      }
    })
  return changed
}

function sanitizeAttributes(object?: ModdleElement | string) {
  if (!object || typeof object === 'string' || !object.$attrs) return
  for (const key of Object.keys(object.$attrs)) {
    if (key.startsWith('$') || key.startsWith('flowable:')) delete object.$attrs[key]
  }
}

function hasUserTaskAssignment(object?: ModdleElement) {
  if (!object || object.$type !== 'bpmn:UserTask') return true
  return Boolean(
    object.assignee ||
    object.candidateUsers ||
    object.candidateGroups ||
    object.loopCharacteristics,
  )
}

function ensureDefaultUserTaskAssignment(element?: BpmnElement) {
  const object = element?.businessObject
  const modeling = service<Modeling>('modeling')
  if (!element || !object || object.$type !== 'bpmn:UserTask' || !modeling) return false
  if (hasUserTaskAssignment(object)) return false
  sanitizeAttributes(object)
  modeling.updateModdleProperties(element, object, {
    candidateUsers: DEFAULT_CANDIDATE_USERS,
  })
  return true
}

function ensureAllUserTaskAssignments() {
  service<ElementRegistry>('elementRegistry')
    ?.getAll()
    .forEach((element) => ensureDefaultUserTaskAssignment(element))
}

function ensureUserTaskElement(element?: BpmnElement) {
  const object = element?.businessObject
  if (!element || !object) return undefined
  if (object.$type === 'bpmn:UserTask') return element
  if (object.$type !== 'bpmn:Task') return undefined
  const nextElement = service<BpmnReplace>('bpmnReplace')?.replaceElement(element, {
    type: 'bpmn:UserTask',
  })
  if (!nextElement) return undefined
  service<Modeling>('modeling')?.updateProperties(nextElement, {
    id: object.id,
    name: object.name || '',
  })
  selectedElement.value = nextElement
  return nextElement
}

function sanitizeModel() {
  ensureAllUserTaskAssignments()
  service<ElementRegistry>('elementRegistry')
    ?.getAll()
    .forEach((element) => {
      const object = element.businessObject
      sanitizeAttributes(object)
      sanitizeAttributes(object?.loopCharacteristics)
      sanitizeAttributes(object?.conditionExpression)
      object?.extensionElements?.values?.forEach(sanitizeAttributes)
    })
}

function syncProcessMetadata() {
  const root = service<Canvas>('canvas')?.getRootElement()
  const object = root?.businessObject
  const modeling = service<Modeling>('modeling')
  if (!root || !object || !modeling) return
  modeling.updateModdleProperties(root, object, {
    id: processForm.key.trim(),
    name: processForm.name.trim(),
    isExecutable: true,
  })
}

async function importXml(xml: string, markDirty: boolean) {
  const cleaned = withoutMisplacedAgentTasks(xml)
  validateBpmnXml(cleaned)
  if (!modeler.value) return
  importing.value = true
  await modeler.value.importXML(cleaned)
  await nextTick()
  sanitizeModel()
  service<Canvas>('canvas')?.zoom('fit-viewport', 'auto')
  resetSelection()
  dirty.value = markDirty
  window.setTimeout(() => (importing.value = false), 0)
}

async function loadVersions(key: string, preferredVersion?: number) {
  loading.value = true
  try {
    versions.value = (await definitionApi.listVersions(key)).sort((a, b) => b.version - a.version)
    if (versions.value.length === 0) return
    try {
      activeVersion.value = await definitionApi.active(key)
    } catch {
      activeVersion.value = undefined
    }
    await openVersion(preferredVersion || versions.value[0]!.version)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openVersion(version: number) {
  if (dirty.value) {
    const confirmed = await confirmAction('Bạn chưa lưu. Đổi phiên bản sẽ mất những thay đổi này.', 'Đổi phiên bản', {
      confirmButtonText: 'Đổi phiên bản',
      cancelButtonText: 'Hủy',
      type: 'warning',
    })
    if (!confirmed) return
  }
  loading.value = true
  try {
    const detail = await definitionApi.detail(processForm.key, version)
    selectedVersion.value = detail.version
    processForm.key = detail.processDefinitionKey
    processForm.name = detail.processDefinitionName
    await importXml(detail.bpmnXml, false)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function saveVersion() {
  if (!processForm.key.trim() || !processForm.name.trim() || !modeler.value) {
    ElMessage.error('Hãy nhập mã và tên quy trình')
    return
  }
  saving.value = true
  try {
    applyServiceCall()
    syncProcessMetadata()
    stripMisplacedAgentBindings()
    normalizeAgentTaskBindings()
    sanitizeModel()
    const exported = await modeler.value.saveXML({ format: true })
    const xml = withoutMisplacedAgentTasks(exported.xml || '')
    validateBpmnXml(xml)
    const result = await definitionApi.deploy({
      processDefinitionKey: processForm.key.trim(),
      processDefinitionName: processForm.name.trim(),
      bpmnXml: xml,
    })
    dirty.value = false
    selectedVersion.value = result.version
    await loadVersions(result.processDefinitionKey, result.version)
    ElMessage.success(`Đã lưu thành ${formatVersion(result.version)}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publishVersion() {
  if (!selectedVersion.value) return
  publishing.value = true
  try {
    activeVersion.value = await definitionApi.activate({
      processDefinitionKey: processForm.key,
      version: selectedVersion.value,
    })
    ElMessage.success(`Đã phát hành ${formatVersion(selectedVersion.value)}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishing.value = false
  }
}

async function inheritRules() {
  const definition = selectedDefinition.value
  if (!definition) return
  const confirmed = await confirmAction(
    'Lấy quy tắc phân công từ phiên bản gần nhất còn cấu hình?',
    'Lấy quy tắc cũ',
    {
      confirmButtonText: 'Kế thừa',
      cancelButtonText: 'Hủy',
    },
  )
  if (!confirmed) return
  inheriting.value = true
  try {
    const result = await assignmentRuleApi.inherit(definition.processDefinitionId)
    const skipped = result.skippedReasons.length
      ? `; bỏ qua: ${result.skippedReasons.join('; ')}`
      : ''
    ElMessage.success(`Đã lấy ${result.copiedCount} quy tắc, bỏ qua ${result.skippedCount}${skipped}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    inheriting.value = false
  }
}

async function deleteVersion() {
  if (!selectedVersion.value) return
  const version = selectedVersion.value
  const confirmed = await confirmAction(
    `Xóa ${processForm.key} ${formatVersion(version)}?`,
    'Xóa phiên bản',
    {
      confirmButtonText: 'Xóa',
      cancelButtonText: 'Hủy',
      type: 'warning',
    },
  )
  if (!confirmed) return
  try {
    await definitionApi.deleteVersion(processForm.key, version)
    ElMessage.success(`${formatVersion(version)} đã được xóa`)
    const remainingVersions = await definitionApi.listVersions(processForm.key)
    if (remainingVersions.length === 0) {
      dirty.value = false
      await router.push('/process-definitions')
      return
    }
    versions.value = remainingVersions.sort((a, b) => b.version - a.version)
    await openVersion(versions.value[0]!.version)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function deleteDefinition() {
  if (!processForm.key) return
  const confirmed = await confirmAction(
    `Xóa mọi phiên bản và cấu hình của “${processForm.name || processForm.key}”? Không hoàn tác được.`,
    'Xóa sơ đồ',
    { confirmButtonText: 'Xóa sơ đồ', cancelButtonText: 'Hủy', type: 'error' },
  )
  if (!confirmed) return
  try {
    await definitionApi.deleteAll(processForm.key)
    dirty.value = false
    ElMessage.success(`Đã xóa sơ đồ: ${processForm.key}`)
    await router.push('/process-definitions')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function openNewProcessDialog() {
  if (dirty.value) {
    const confirmed = await confirmAction(
      'Thay đổi hiện tại chưa được lưu. Tạo quy trình mới sẽ mất các thay đổi đó.',
      'Tạo sơ đồ mới',
      {
        confirmButtonText: 'Tiếp tục tạo',
        cancelButtonText: 'Hủy',
        type: 'warning',
      },
    )
    if (!confirmed) return
  }
  Object.assign(newProcessForm, { key: '', name: '' })
  newProcessVisible.value = true
}

async function createNewProcess() {
  let identity
  try {
    identity = normalizeProcessIdentity(newProcessForm.key, newProcessForm.name)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    return
  }
  const { key, name } = identity
  newProcessSaving.value = true
  try {
    if (await definitionApi.exists(key)) return ElMessage.error(`Mã quy trình đã có: ${key}`)
    processForm.key = key
    processForm.name = name
    versions.value = []
    activeVersion.value = undefined
    selectedVersion.value = undefined
    await importXml(createBlankBpmn(key, name), true)
    newProcessVisible.value = false
    await router.replace({ name: 'process-designer', query: { key, name } })
    ElMessage.success(`Đã tạo quy trình: ${name}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    newProcessSaving.value = false
  }
}

interface ElementFormSnapshot {
  id: string
  name: string
  documentation: string
  conditionExpression: string
}

function getElementFormSnapshot(): ElementFormSnapshot {
  return { ...elementForm }
}

function applyBaseProperties(
  announce = true,
  element = selectedElement.value,
  values: ElementFormSnapshot = getElementFormSnapshot(),
) {
  const object = element?.businessObject
  const modeling = service<Modeling>('modeling')
  const registry = service<ElementRegistry>('elementRegistry')
  if (!element || !object || !modeling) return
  const nextId = values.id.trim()
  if (!nextId) return ElMessage.error('Hãy nhập mã phần tử')
  const existing = registry?.get(nextId)
  if (existing && existing !== element) return ElMessage.error('Mã này đã có')
  const nextName = values.name.trim()
  const baseProperties: Record<string, unknown> = {}
  if (object.id !== nextId) baseProperties.id = nextId
  if ((object.name || '') !== nextName) baseProperties.name = nextName
  if (Object.keys(baseProperties).length) modeling.updateProperties(element, baseProperties)
  let conditionChanged = false
  if (object.$type === 'bpmn:SequenceFlow') {
    const nextExpression = values.conditionExpression.trim()
    const currentExpression = readExpression(object.conditionExpression)
    const expression = nextExpression
      ? service<Moddle>('moddle')?.create('bpmn:FormalExpression', {
          body: nextExpression,
        })
      : undefined
    conditionChanged = currentExpression !== nextExpression
    if (conditionChanged) {
      modeling.updateModdleProperties(element, object, { conditionExpression: expression })
    }
  }
  const nextDocumentation = values.documentation.trim()
  const currentDocumentation =
    object.documentation?.map((item) => item.text || item.body || '').join('\n') || ''
  const documentation = nextDocumentation
    ? [
        service<Moddle>('moddle')?.create('bpmn:Documentation', {
          text: nextDocumentation,
        }),
      ]
    : []
  if (currentDocumentation !== nextDocumentation) {
    modeling.updateModdleProperties(element, object, { documentation })
  }
  if (
    Object.keys(baseProperties).length ||
    currentDocumentation !== nextDocumentation ||
    conditionChanged
  ) {
    dirty.value = true
  }
  if (announce) ElMessage.success('Đã áp thuộc tính phần tử')
}

function applyBasePropertiesAfterBlur() {
  const element = elementBeingEdited.value || selectedElement.value
  const values = getElementFormSnapshot()
  const sequence = elementEditSequence
  window.setTimeout(() => {
    applyBaseProperties(false, element, values)
    if (sequence === elementEditSequence) elementBeingEdited.value = undefined
  }, 0)
}

function beginBasePropertiesEdit() {
  elementBeingEdited.value = selectedElement.value
  elementEditSequence += 1
}

function applyMode(mode: ApprovalMode) {
  approvalMode.value = mode
  if (!mode) return
  const element = ensureUserTaskElement(selectedElement.value)
  const object = element?.businessObject
  const modeling = service<Modeling>('modeling')
  const moddle = service<Moddle>('moddle')
  if (!element || !object || !modeling || !moddle) return
  const properties: Record<string, unknown> = {
    assignee: undefined,
    candidateUsers: undefined,
    candidateGroups: undefined,
    loopCharacteristics: undefined,
  }
  if (mode === 'single') properties.assignee = DEFAULT_ASSIGNEE
  if (mode === 'candidate') {
    properties.candidateUsers = DEFAULT_CANDIDATE_USERS
  }
  if (mode === 'parallel') {
    properties.assignee = '${assignee}'
    properties.loopCharacteristics = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
      isSequential: false,
      collection: DEFAULT_COUNTERSIGN_COLLECTION,
      elementVariable: 'assignee',
      completionCondition: moddle.create('bpmn:FormalExpression', {
        body: '${nrOfCompletedInstances == nrOfInstances}',
      }),
    })
  }
  modeling.updateModdleProperties(element, object, properties)
  resetSelection(element)
  dirty.value = true
}

function writeListeners(kind: ListenerKind, values: string[]) {
  const element = selectedElement.value
  const object = element?.businessObject
  const modeling = service<Modeling>('modeling')
  const moddle = service<Moddle>('moddle')
  if (!element || !object || !modeling || !moddle) return
  const spec = {
    executionStart: { type: 'flowable:ExecutionListener', event: 'start' },
    executionEnd: { type: 'flowable:ExecutionListener', event: 'end' },
    taskCreate: { type: 'flowable:TaskListener', event: 'create' },
    taskComplete: { type: 'flowable:TaskListener', event: 'complete' },
  }[kind]
  let extensionElements = object.extensionElements
  if (!extensionElements) {
    extensionElements = moddle.create('bpmn:ExtensionElements', { values: [] })
    modeling.updateModdleProperties(element, object, { extensionElements })
  }
  const retained = (extensionElements.values || []).filter(
    (item) => !(item.$type === spec.type && item.event === spec.event),
  )
  const created = values.map((value) =>
    moddle.create(spec.type, { event: spec.event, delegateExpression: `\${${value}}` }),
  )
  modeling.updateModdleProperties(element, extensionElements, { values: [...retained, ...created] })
  dirty.value = true
}

function addListener() {
  const value = normalizeListener(listenerForm.bean)
  if (!value) return ElMessage.error('Hãy nhập tên xử lý')
  if (listenerForm.kind.startsWith('task') && !isTask.value) {
    return ElMessage.error('Việc này chỉ gắn được trên nhiệm vụ')
  }
  if (!listeners[listenerForm.kind].includes(value)) listeners[listenerForm.kind].push(value)
  writeListeners(listenerForm.kind, listeners[listenerForm.kind])
  listenerForm.bean = ''
}

function removeListener(kind: ListenerKind, index: number) {
  listeners[kind].splice(index, 1)
  writeListeners(kind, listeners[kind])
}

async function upload(file?: File) {
  if (!file) return
  try {
    const xml = withoutMisplacedAgentTasks(await file.text())
    const document = validateBpmnXml(xml)
    const process = Array.from(document.getElementsByTagName('*')).find(
      (node) => node.localName === 'process',
    )
    processForm.key = process?.getAttribute('id') || processForm.key
    processForm.name = process?.getAttribute('name') || processForm.key
    await importXml(xml, true)
    ElMessage.success(`Đã nhập ${file.name}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function download() {
  if (!modeler.value) return
  sanitizeModel()
  const { xml } = await modeler.value.saveXML({ format: true })
  const url = URL.createObjectURL(new Blob([xml], { type: 'application/xml;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${processForm.key || 'workflow'}.bpmn20.xml`
  anchor.click()
  URL.revokeObjectURL(url)
}

type DesignerToolbarCommand = 'import' | 'export' | 'new-process'

function handleToolbarCommand(command: DesignerToolbarCommand) {
  if (command === 'import') fileInput.value?.click()
  else if (command === 'export') void download()
  else void openNewProcessDialog()
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => (dirty.value ? window.confirm('Sơ đồ chưa được lưu. Rời trang?') : true))

onMounted(async () => {
  if (!canvasElement.value) return
  modeler.value = new BpmnModeler({
    container: canvasElement.value,
    additionalModules: [workflowPaletteModule],
    moddleExtensions: { flowable: flowableModdle, workflow: workflowModdle },
  })
  modeler.value.on('selection.changed', (event) => resetSelection(event.newSelection?.[0]))
  modeler.value.on('commandStack.changed', () => {
    if (!importing.value) dirty.value = true
  })
  modeler.value.on('shape.added', (event) => {
    const element = event.element
    window.setTimeout(() => {
      const changed = ensureDefaultUserTaskAssignment(element)
      if (changed && selectedElement.value?.id === element?.id) {
        resetSelection(element)
      }
    }, 0)
  })
  canvasResizeObserver = new ResizeObserver(() => {
    service<Canvas>('canvas')?.resized()
  })
  canvasResizeObserver.observe(canvasElement.value)
  window.addEventListener('beforeunload', beforeUnload)
  if (agentsEnabled) await loadAgentVersions()

  const key = String(route.query.key || '')
  const name = String(route.query.name || key)
  const draft = String(route.query.draft || '')
  processForm.key = key
  processForm.name = name
  if (draft) {
    let xml = draft
    try {
      xml = decodeURIComponent(draft)
    } catch {
      /* query was already decoded */
    }
    await importXml(xml, true)
  } else if (key) {
    await loadVersions(key)
  } else {
    await importXml(createBlankBpmn('new_process', 'Quy trình mới'), true)
    processForm.key = 'new_process'
    processForm.name = 'Quy trình mới'
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  canvasResizeObserver?.disconnect()
  modeler.value?.destroy()
})

watch(selectedVersion, () => resetSelection())
</script>

<template>
  <div class="designer-page" v-loading="loading">
    <header class="designer-toolbar">
      <div class="designer-heading">
        <button
          class="designer-back-button"
          type="button"
          @click="router.push('/process-definitions')"
        >
          <ArrowLeft :size="17" /><span>Quay lại</span>
        </button>
        <span class="designer-heading-divider" />
        <div>
          <span class="designer-breadcrumb">Quy trình / Sửa</span>
          <div class="designer-title-row">
            <strong>{{ processForm.name || processForm.key || 'Quy trình chưa đặt tên' }}</strong>
            <span v-if="dirty" class="dirty-badge">Thay đổi chưa lưu</span>
          </div>
        </div>
      </div>
      <div class="designer-toolbar-actions">
        <div class="designer-toolbar-secondary">
          <el-button @click="fileInput?.click()"><FileUp :size="16" />Nhập</el-button>
          <el-button @click="download"><Download :size="16" />Xuất</el-button>
          <span class="toolbar-action-divider" />
          <el-button @click="openNewProcessDialog"><Plus :size="16" />Tạo sơ đồ mới</el-button>
        </div>
        <el-dropdown class="designer-more-actions" trigger="click" @command="handleToolbarCommand">
          <el-button aria-label="Thêm thao tác sơ đồ">
            <MoreHorizontal :size="17" /><span class="designer-action-label">Thêm</span>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="import"><FileUp :size="15" />Nhập sơ đồ</el-dropdown-item>
              <el-dropdown-item command="export"
                ><Download :size="15" />Xuất sơ đồ</el-dropdown-item
              >
              <el-dropdown-item divided command="new-process">
                <Plus :size="15" />Tạo sơ đồ mới
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          class="designer-save-button"
          type="primary"
          aria-label="Lưu phiên bản mới"
          :loading="saving"
          @click="saveVersion"
        >
          <Save :size="16" /><span class="designer-action-label">Lưu phiên bản mới</span>
        </el-button>
      </div>
      <input
        ref="fileInput"
        type="file"
        accept=".bpmn,.xml,.bpmn20.xml"
        hidden
        @change="upload(($event.target as HTMLInputElement).files?.[0])"
      />
    </header>

    <div class="designer-workspace">
      <div ref="canvasElement" class="bpmn-canvas" />
      <aside class="properties-panel">
        <section class="inspector-card">
          <div class="inspector-heading">
            <div>
              <h2>Tổng quan quy trình</h2>
              <p>Mã này không đổi sau khi tạo.</p>
            </div>
          </div>
          <el-form label-position="top" size="small">
            <el-form-item label="Mã quy trình">
              <el-input :model-value="processForm.key" readonly />
            </el-form-item>
            <el-form-item label="Tên quy trình">
              <el-input :model-value="processForm.name" aria-label="Tên quy trình" readonly />
            </el-form-item>
          </el-form>
        </section>

        <section class="inspector-card">
          <div class="inspector-heading">
            <div>
              <h2>Quản lý phiên bản</h2>
              <p>Lưu tạo phiên bản mới. Phát hành để mọi người bắt đầu được.</p>
            </div>
          </div>
          <div class="version-status-row">
            <span>Đang dùng</span>
            <StatusBadge
              :status="activeVersion ? 'PUBLISHED' : 'PENDING'"
              :label="activeVersion ? formatVersion(activeVersion.version) : 'Chưa phát hành'"
            />
          </div>
          <el-select
            :model-value="selectedVersion"
            placeholder="Chưa có phiên bản"
            :disabled="versions.length === 0 || loading"
            @change="openVersion"
          >
            <el-option
              v-for="item in versions"
              :key="item.version"
              :label="`${formatVersion(item.version)} · ${formatDateTime(item.deployedAt)}`"
              :value="item.version"
            />
          </el-select>
          <el-button
            type="primary"
            :disabled="!selectedVersion || isSelectedActive"
            :loading="publishing"
            @click="publishVersion"
          >
            {{ isSelectedActive ? 'Phiên bản này đang được dùng' : 'Phát hành phiên bản này' }}
          </el-button>
          <el-button :disabled="!selectedDefinition" :loading="inheriting" @click="inheritRules">
            <Copy :size="15" />Kế thừa cấu hình
          </el-button>
          <div class="danger-zone">
            <span>Xóa</span>
            <el-button type="danger" plain :disabled="!selectedVersion" @click="deleteVersion">
              <Trash2 :size="15" />Xóa phiên bản hiện tại
            </el-button>
            <el-button type="danger" plain :disabled="!versions.length" @click="deleteDefinition">
              <Trash2 :size="15" />Xóa sơ đồ
            </el-button>
          </div>
        </section>

        <section class="inspector-card element-card">
          <div class="inspector-heading">
            <div>
              <h2>Cấu hình phần tử</h2>
              <p>Chọn một bước trên sơ đồ để sửa.</p>
            </div>
          </div>
          <template v-if="selectedElement">
            <div class="property-block base-properties">
              <strong>Thông tin cơ bản</strong>
              <el-form label-position="top" size="small">
                <el-form-item label="Mã phần tử">
                  <el-input
                    v-model="elementForm.id"
                    @focus="beginBasePropertiesEdit"
                    @blur="applyBasePropertiesAfterBlur"
                  />
                </el-form-item>
                <el-form-item label="Tên phần tử">
                  <el-input
                    v-model="elementForm.name"
                    @focus="beginBasePropertiesEdit"
                    @blur="applyBasePropertiesAfterBlur"
                  />
                </el-form-item>
                <el-form-item label="Mô tả">
                  <el-input
                    v-model="elementForm.documentation"
                    type="textarea"
                    :rows="3"
                    @focus="beginBasePropertiesEdit"
                    @blur="applyBasePropertiesAfterBlur"
                  />
                </el-form-item>
              </el-form>
            </div>

            <AgentTaskConfigPanel
              v-if="isAgentTask && agentsEnabled"
              :agent-version-id="agentVersionId"
              :agent-versions="agentVersions"
              :selected-agent-version="selectedAgentVersion"
              :agent-version-search-loading="agentVersionSearchLoading"
              :agent-input-schema-fields="agentInputSchemaFields"
              :agent-output-schema-fields="agentOutputSchemaFields"
              :agent-mapping-rows="agentMappingRows"
              :agent-output-mapping-rows="agentOutputMappingRows"
              :agent-process-wait-timeout-seconds="agentProcessWaitTimeoutSeconds"
              :agent-process-failure-policy="agentProcessFailurePolicy"
              :search-agent-versions="searchAgentVersions"
              :schema-field-label="schemaFieldLabel"
              :apply-agent-version="applyAgentVersion"
              :apply-agent-mappings="applyAgentMappings"
              :add-agent-mapping-row="addAgentMappingRow"
              :remove-agent-mapping-row="removeAgentMappingRow"
              :add-agent-output-mapping-row="addAgentOutputMappingRow"
              :remove-agent-output-mapping-row="removeAgentOutputMappingRow"
              :apply-agent-process-policy="applyAgentProcessPolicy"
              @update:agent-version-id="agentVersionId = $event"
              @update:agent-process-wait-timeout-seconds="agentProcessWaitTimeoutSeconds = $event"
              @update:agent-process-failure-policy="agentProcessFailurePolicy = $event"
            />

            <ServiceCallConfigPanel
              v-if="isServiceTask"
              :service-key="serviceCallKey"
              :operation-key="serviceOperationKey"
              :services="publishedServices"
              :input-rows="serviceInputRows"
              :output-rows="serviceOutputRows"
              @update:service-key="onServiceKey"
              @update:operation-key="onOperationKey"
              @update:input-rows="serviceInputRows = $event"
              @update:output-rows="serviceOutputRows = $event"
              @apply="applyServiceCall"
            />

            <div v-if="!isAgentTask && (isTask || isSequenceFlow)" class="property-block">
              <strong>Luật chuyển</strong>
              <el-form label-position="top" size="small">
                <el-form-item v-if="showsApprovalMode(isTask, isServiceTask)" label="Loại bước">
                  <el-select
                    v-model="approvalMode"
                    placeholder="Hãy chọn loại bước"
                    @change="applyMode"
                  >
                    <el-option label="Bước một người" value="single" />
                    <el-option label="Bước người ứng viên" value="candidate" />
                    <el-option label="Bước ký đồng thời" value="parallel" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="isSequenceFlow" label="Điều kiện đường nối">
                  <el-input
                    v-model="elementForm.conditionExpression"
                    type="textarea"
                    :rows="3"
                    placeholder="${operationType == 'agree'}"
                    @focus="beginBasePropertiesEdit"
                    @blur="applyBasePropertiesAfterBlur"
                  />
                </el-form-item>
              </el-form>
            </div>

            <div class="property-block technical-info">
              <strong>Thông tin kỹ thuật</strong>
              <dl>
                <dt>Loại</dt>
                <dd>{{ elementTypeLabel }}</dd>
                <template v-if="selectedSource || selectedTarget">
                  <dt>Nguồn</dt>
                  <dd>{{ selectedSource || '-' }}</dd>
                  <dt>Đích</dt>
                  <dd>{{ selectedTarget || '-' }}</dd>
                </template>
                <template v-if="elementForm.conditionExpression">
                  <dt>Điều kiện</dt>
                  <dd>
                    <code>{{ elementForm.conditionExpression }}</code>
                  </dd>
                </template>
                <template v-if="elementForm.documentation">
                  <dt>Mô tả</dt>
                  <dd>{{ elementForm.documentation }}</dd>
                </template>
              </dl>
              <div v-if="!isAgentTask && extensionAttributes.length" class="extension-attributes">
                <p v-for="attribute in extensionAttributes" :key="attribute.key">
                  <span>{{ attribute.key }}</span
                  ><code>{{ attribute.value }}</code>
                </p>
              </div>
            </div>

            <div v-if="!isAgentTask" class="property-block listener-editor">
              <strong>Khi nào chạy</strong>
              <el-select v-model="listenerForm.kind">
                <el-option
                  v-for="section in availableListenerSections"
                  :key="section.kind"
                  :label="section.label"
                  :value="section.kind"
                />
              </el-select>
              <div class="listener-input">
                <el-input
                  v-model="listenerForm.bean"
                  placeholder="Tên bean của bộ lắng nghe"
                  @keyup.enter="addListener"
                />
                <el-button @click="addListener">Thêm</el-button>
              </div>
              <div class="listener-table">
                <div class="listener-table-head">
                  <span>Số thứ tự</span><span>Loại sự kiện</span><span>Bean bộ lắng nghe</span><span>Thao tác</span>
                </div>
                <div v-if="listenerRows.length === 0" class="listener-empty-row">Chưa có dữ liệu</div>
                <template v-else>
                  <div
                    v-for="(row, index) in listenerRows"
                    :key="`${row.kind}-${row.listener}-${row.index}`"
                    class="listener-table-row"
                  >
                    <span>{{ index + 1 }}</span>
                    <span>{{ row.label }}</span>
                    <code>{{ row.listener }}</code>
                    <button
                      class="listener-remove"
                      type="button"
                      aria-label="Xóa bộ lắng nghe"
                      @click="removeListener(row.kind, row.index)"
                    >
                      ×
                    </button>
                  </div>
                </template>
              </div>
            </div>
          </template>
          <p v-else class="property-empty">Bấm một bước trên sơ đồ để xem và sửa.</p>
        </section>
      </aside>
    </div>

    <el-dialog v-model="newProcessVisible" title="Tạo sơ đồ mới" width="480px" destroy-on-close>
      <el-form label-position="top" @submit.prevent="createNewProcess">
        <el-form-item label="Mã quy trình" required>
          <el-input
            v-model="newProcessForm.key"
            maxlength="64"
            placeholder="applyApprovalProcess"
          />
        </el-form-item>
        <el-form-item label="Tên định nghĩa quy trình" required>
          <el-input v-model="newProcessForm.name" maxlength="128" placeholder="Quy trình phê duyệt đơn" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newProcessVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="newProcessSaving" @click="createNewProcess">
          Tạo khung vẽ trống
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
