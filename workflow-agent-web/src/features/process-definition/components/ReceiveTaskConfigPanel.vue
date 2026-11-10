<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  BpmnElement,
  BpmnModelerInstance,
  Moddle,
  ModdleElement,
  Modeling,
} from '@/bpmn/modeler-types'
import { addTimeoutBoundary, hasTimerBoundary, waitTaskWarnings } from '@/bpmn/waitTaskModel'
import {
  eventWaitKind,
  readEventWait,
  withoutEventExtensions,
  type EventWaitEvent,
} from '@/bpmn/eventWaitModel'
import { eventsEnabled } from '@/config/product'
import { confirmAction } from '@/utils/confirmation'
import EventWaitConfigPanel from './EventWaitConfigPanel.vue'

type WaitMode = 'manual' | 'event'

const props = withDefaults(
  defineProps<{
    element: BpmnElement
    modeler: BpmnModelerInstance
    /** Published events of the tenant, for the event mode. */
    events?: EventWaitEvent[]
    readOnly?: boolean
  }>(),
  { events: () => [], readOnly: false },
)

const asyncLeave = ref(readAsyncLeave())
const mode = ref<WaitMode>(readMode())
// Bumped after changes bpmn-js makes outside Vue's reactivity (attaching a boundary event).
const revision = ref(0)
const host = computed(() => props.element as BpmnElement & { attachers?: BpmnElement[] })
const hasTimeout = computed(() => revision.value >= 0 && hasTimerBoundary(host.value))
const warnings = computed(() =>
  waitTaskWarnings({ asyncLeave: asyncLeave.value, hasTimeout: hasTimeout.value }),
)
/** An imported event wait stays visible when the flag is off; switching to it is not offered. */
const showsModes = computed(() => eventsEnabled || mode.value === 'event')

watch(
  () => props.element,
  () => {
    asyncLeave.value = readAsyncLeave()
    mode.value = readMode()
    revision.value++
  },
)

function readAsyncLeave() {
  return props.element.businessObject?.asyncLeave === true
}

function readMode(): WaitMode {
  return eventWaitKind(props.element.businessObject) === 'receiveTask' ? 'event' : 'manual'
}

function currentValues(): ModdleElement[] {
  return props.element.businessObject?.extensionElements?.values ?? []
}

async function switchMode(next: WaitMode) {
  const object = props.element.businessObject
  if (props.readOnly || !object || next === mode.value) return
  const modeling = props.modeler.get('modeling') as Modeling
  const moddle = props.modeler.get('moddle') as Moddle
  if (next === 'event') {
    modeling.updateModdleProperties(props.element, object, {
      asyncLeave: true,
      extensionElements: moddle.create('bpmn:ExtensionElements', {
        values: [moddle.create('flowable:EventType', { body: '' }), ...currentValues()],
      }),
    })
    asyncLeave.value = true
    mode.value = 'event'
    return
  }
  const configured = readEventWait(object)
  if (
    (configured.eventKey || configured.correlations.length || configured.outputs.length) &&
    !(await confirmAction(
      'Cấu hình sự kiện của bước này sẽ bị xóa.',
      'Chuyển về chờ tiếp tục thủ công?',
    ))
  ) {
    return
  }
  const kept = withoutEventExtensions(currentValues())
  modeling.updateModdleProperties(props.element, object, {
    extensionElements: kept.length
      ? moddle.create('bpmn:ExtensionElements', { values: kept })
      : undefined,
  })
  mode.value = 'manual'
}

function applyAsyncLeave(value: boolean) {
  asyncLeave.value = value
  const modeling = props.modeler.get('modeling') as Modeling
  modeling.updateProperties(props.element, { asyncLeave: value ? true : undefined })
}

function addTimeout() {
  addTimeoutBoundary(props.modeler, host.value)
  revision.value++
}
</script>

<template>
  <div class="property-block">
    <strong>Bước chờ</strong>
    <el-radio-group
      v-if="showsModes"
      class="wait-mode"
      :model-value="mode"
      :disabled="readOnly"
      size="small"
      @update:model-value="switchMode($event as WaitMode)"
    >
      <el-radio value="manual">Chờ tiếp tục thủ công</el-radio>
      <el-radio v-if="eventsEnabled || mode === 'event'" value="event">Chờ sự kiện</el-radio>
    </el-radio-group>
  </div>

  <EventWaitConfigPanel
    v-if="mode === 'event'"
    :element="element"
    :modeler="modeler"
    :events="events"
    :read-only="readOnly"
  />

  <div v-else class="property-block">
    <p class="property-hint">
      Công việc dừng ở đây cho tới khi người vận hành hoặc hệ thống bấm "Tiếp tục" trên màn chi tiết
      công việc.
    </p>
    <el-form label-position="top" size="small">
      <el-form-item>
        <el-checkbox
          :model-value="asyncLeave"
          @update:model-value="applyAsyncLeave(Boolean($event))"
        >
          Rời bước bất đồng bộ
        </el-checkbox>
      </el-form-item>
      <el-form-item label="Hạn chờ">
        <span v-if="hasTimeout" class="property-hint">
          Đã có hạn chờ. Chọn sự kiện hẹn giờ gắn trên bước để sửa thời gian.
        </span>
        <el-button v-else size="small" @click="addTimeout">Thêm hạn chờ</el-button>
      </el-form-item>
    </el-form>
    <el-alert
      v-for="warning in warnings"
      :key="warning"
      :title="warning"
      type="warning"
      :closable="false"
      show-icon
    />
  </div>
</template>
