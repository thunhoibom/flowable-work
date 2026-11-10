<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type {
  BpmnElement,
  BpmnModelerInstance,
  Moddle,
  ModdleElement,
  Modeling,
} from '@/bpmn/modeler-types'
import {
  correlationFields,
  eventFields,
  eventWaitExtensions,
  eventWaitKind,
  eventWaitProblems,
  eventWaitWarnings,
  readEventWait,
  type EventWaitEvent,
  type EventWaitForm,
} from '@/bpmn/eventWaitModel'
import { addTimeoutBoundary, hasTimerBoundary } from '@/bpmn/waitTaskModel'

const props = defineProps<{
  element: BpmnElement
  modeler: BpmnModelerInstance
  /** Published events of the tenant. */
  events: EventWaitEvent[]
  readOnly?: boolean
}>()

const form = reactive<EventWaitForm>(readEventWait(props.element.businessObject))
const asyncLeave = ref(readAsyncLeave())
// Bumped after changes bpmn-js makes outside Vue's reactivity (attaching a boundary event).
const revision = ref(0)

const kind = computed(() => eventWaitKind(props.element.businessObject) ?? 'receiveTask')
const event = computed(() => props.events.find((item) => item.key === form.eventKey.trim()))
const fields = computed(() => eventFields(event.value))
const missingCorrelations = computed(() => {
  const used = new Set(form.correlations.map((correlation) => correlation.name))
  return correlationFields(event.value).filter((name) => !used.has(name))
})
const hasTimeout = computed(
  () => revision.value >= 0 && hasTimerBoundary(props.element as { attachers?: BpmnElement[] }),
)
const elementVariable = computed(() => {
  const loop = props.element.businessObject?.loopCharacteristics
  return loop?.$type === 'bpmn:MultiInstanceLoopCharacteristics'
    ? String(loop.elementVariable ?? '')
    : undefined
})
const problems = computed(() => eventWaitProblems(form, event.value))
const warnings = computed(() =>
  eventWaitWarnings({
    form,
    event: event.value,
    multiInstanceElementVariable: elementVariable.value,
    asyncLeave: asyncLeave.value,
    hasTimeout: kind.value === 'receiveTask' ? hasTimeout.value : undefined,
  }),
)

watch(
  () => props.element,
  (element) => {
    Object.assign(form, readEventWait(element.businessObject))
    asyncLeave.value = readAsyncLeave()
    revision.value++
  },
)

function readAsyncLeave() {
  return props.element.businessObject?.asyncLeave === true
}

function apply() {
  const object = props.element.businessObject
  if (props.readOnly || !object) return
  const modeling = props.modeler.get('modeling') as Modeling
  const moddle = props.modeler.get('moddle') as Moddle
  const current: ModdleElement[] = object.extensionElements?.values ?? []
  modeling.updateModdleProperties(props.element, object, {
    extensionElements: moddle.create('bpmn:ExtensionElements', {
      values: eventWaitExtensions(moddle, current, form),
    }),
  })
}

function selectEvent(key: string) {
  form.eventKey = key
  const selected = props.events.find((item) => item.key === key)
  const names = new Set(eventFields(selected).map((field) => field.name))
  form.correlations = correlationFields(selected).map((name) => ({ name, value: `\${${name}}` }))
  form.outputs = form.outputs.filter((output) => !output.source || names.has(output.source))
  apply()
}

function updateCorrelation(index: number, value: string) {
  form.correlations = form.correlations.map((row, rowIndex) =>
    rowIndex === index ? { ...row, value } : row,
  )
}

function removeCorrelation(index: number) {
  form.correlations = form.correlations.filter((_, rowIndex) => rowIndex !== index)
  apply()
}

function addCorrelation(name: string) {
  form.correlations = [...form.correlations, { name, value: `\${${name}}` }]
  apply()
}

function updateOutput(index: number, patch: Partial<EventWaitForm['outputs'][number]>) {
  form.outputs = form.outputs.map((row, rowIndex) =>
    rowIndex === index ? { ...row, ...patch } : row,
  )
}

/** Select and checkbox changes are complete edits: write them at once. */
function changeOutput(index: number, patch: Partial<EventWaitForm['outputs'][number]>) {
  updateOutput(index, patch)
  apply()
}

function addOutput() {
  form.outputs = [...form.outputs, { source: '', target: '', transient: false }]
}

function removeOutput(index: number) {
  form.outputs = form.outputs.filter((_, rowIndex) => rowIndex !== index)
  apply()
}

function applyAsyncLeave(value: boolean) {
  asyncLeave.value = value
  if (props.readOnly) return
  const modeling = props.modeler.get('modeling') as Modeling
  modeling.updateProperties(props.element, { asyncLeave: value ? true : undefined })
}

function addTimeout() {
  addTimeoutBoundary(props.modeler, props.element)
  revision.value++
}
</script>

<template>
  <div class="property-block event-wait-panel">
    <strong>Chờ sự kiện</strong>
    <p class="property-hint">
      Công việc dừng ở đây cho tới khi hệ thống khác gửi sự kiện có cùng giá trị tương quan.
    </p>
    <el-form label-position="top" size="small">
      <el-form-item label="Sự kiện">
        <el-select
          class="event-select"
          :model-value="form.eventKey"
          filterable
          :disabled="readOnly"
          placeholder="Chọn sự kiện đã phát hành"
          @update:model-value="selectEvent(String($event))"
        >
          <el-option
            v-for="item in events"
            :key="item.key"
            :label="`${item.name || item.key} (${item.key})`"
            :value="item.key"
          />
        </el-select>
        <p v-if="event?.description" class="property-hint">{{ event.description }}</p>
        <ul v-if="fields.length" class="event-fields">
          <li v-for="field in fields" :key="field.name">
            <code>{{ field.name }}</code>
            <small>{{ field.type }}{{ field.correlation ? ' · tương quan' : '' }}</small>
          </li>
        </ul>
      </el-form-item>

      <el-form-item label="Tương quan">
        <div
          v-for="(row, index) in form.correlations"
          :key="`correlation-${row.name}-${index}`"
          class="correlation-row"
        >
          <code>{{ row.name }}</code>
          <el-input
            class="correlation-value"
            :model-value="row.value"
            :disabled="readOnly"
            placeholder="${biến}"
            @update:model-value="updateCorrelation(index, String($event))"
            @blur="apply"
          />
          <el-button
            class="remove-correlation"
            text
            :disabled="readOnly"
            @click="removeCorrelation(index)"
          >
            Xóa
          </el-button>
        </div>
        <div v-if="missingCorrelations.length && !readOnly" class="add-correlations">
          <el-button
            v-for="name in missingCorrelations"
            :key="name"
            class="add-correlation"
            size="small"
            @click="addCorrelation(name)"
          >
            Thêm {{ name }}
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="Dữ liệu nhận">
        <div v-for="(row, index) in form.outputs" :key="`output-${index}`" class="output-row">
          <el-select
            class="output-source"
            :model-value="row.source"
            :disabled="readOnly"
            placeholder="Trường"
            @update:model-value="changeOutput(index, { source: String($event) })"
          >
            <el-option
              v-for="field in fields"
              :key="field.name"
              :label="field.name"
              :value="field.name"
            />
          </el-select>
          <el-input
            class="output-target"
            :model-value="row.target"
            :disabled="readOnly"
            placeholder="Lưu vào biến"
            @update:model-value="updateOutput(index, { target: String($event) })"
            @blur="apply"
          />
          <el-checkbox
            :model-value="row.transient"
            :disabled="readOnly"
            @update:model-value="changeOutput(index, { transient: Boolean($event) })"
          >
            Chỉ dùng tạm
          </el-checkbox>
          <el-button text :disabled="readOnly" @click="removeOutput(index)">Xóa</el-button>
        </div>
        <el-button class="add-output" size="small" :disabled="readOnly" @click="addOutput">
          Thêm
        </el-button>
      </el-form-item>

      <el-form-item v-if="kind === 'receiveTask'" label="Hạn chờ">
        <span v-if="hasTimeout" class="property-hint">
          Đã có hạn chờ. Chọn sự kiện hẹn giờ gắn trên bước để sửa thời gian.
        </span>
        <el-button v-else size="small" :disabled="readOnly" @click="addTimeout">
          Thêm hạn chờ
        </el-button>
      </el-form-item>
      <p v-else-if="kind === 'intermediateCatch'" class="property-hint">
        Muốn có hạn chờ, đặt sự kiện sau cổng rẽ theo sự kiện cùng một sự kiện hẹn giờ
      </p>

      <el-form-item>
        <el-checkbox
          :model-value="asyncLeave"
          :disabled="readOnly"
          @update:model-value="applyAsyncLeave(Boolean($event))"
        >
          Rời bước bất đồng bộ
        </el-checkbox>
      </el-form-item>
    </el-form>
    <el-alert
      v-for="problem in problems"
      :key="problem"
      class="event-wait-problem"
      :title="problem"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-for="warning in warnings"
      :key="warning"
      class="event-wait-warning"
      :title="warning"
      type="warning"
      :closable="false"
      show-icon
    />
  </div>
</template>

<style scoped>
.event-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.event-fields small {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
}

.correlation-row,
.output-row {
  display: grid;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
  align-items: center;
}

.correlation-row {
  grid-template-columns: minmax(70px, auto) 1fr auto;
}

.output-row {
  grid-template-columns: 1fr 1fr auto auto;
}

.add-correlations {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
