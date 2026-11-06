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
  definitionsOf,
  eventDefinition,
  eventKind,
  readErrorCode,
  readTimer,
  timerWarnings,
  type TimerForm,
} from '@/bpmn/subProcessModel'

const props = defineProps<{
  element: BpmnElement
  modeler: BpmnModelerInstance
}>()

const ERROR_CODE = /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/

const kind = computed(() => eventKind(props.element.businessObject))
const timer = reactive<TimerForm>(readTimer(eventDefinition(props.element.businessObject)))
const errorCode = ref(readErrorCode(eventDefinition(props.element.businessObject)))
const warnings = computed(() => {
  if (kind.value === 'timer') return timerWarnings(timer)
  if (kind.value === 'error' && !ERROR_CODE.test(errorCode.value.trim())) {
    return ['Mã lỗi chỉ gồm chữ, số, _ . - và bắt đầu bằng chữ']
  }
  return []
})

watch(
  () => props.element,
  (element) => {
    const definition = eventDefinition(element.businessObject)
    Object.assign(timer, readTimer(definition))
    errorCode.value = readErrorCode(definition)
  },
)

function services() {
  return {
    modeling: props.modeler.get('modeling') as Modeling,
    moddle: props.modeler.get('moddle') as Moddle,
  }
}

function applyTimer() {
  const definition = eventDefinition(props.element.businessObject)
  if (!definition) return
  const { modeling, moddle } = services()
  modeling.updateModdleProperties(props.element, definition, {
    timeDuration: undefined,
    timeDate: undefined,
    timeCycle: undefined,
    [timer.kind]: timer.value.trim()
      ? moddle.create('bpmn:FormalExpression', { body: timer.value.trim() })
      : undefined,
  })
}

/** Reuses the bpmn:Error with this code, or adds one to the definitions. */
function applyError() {
  const definition = eventDefinition(props.element.businessObject)
  const definitions = definitionsOf(props.element.businessObject)
  const code = errorCode.value.trim()
  if (!definition || !definitions || !code) return
  const { modeling, moddle } = services()
  const rootElements = (definitions.rootElements as ModdleElement[] | undefined) ?? []
  let error = rootElements.find(
    (candidate) => candidate.$type === 'bpmn:Error' && candidate.errorCode === code,
  )
  if (!error) {
    error = moddle.create('bpmn:Error', {
      id: `Error_${code.replace(/[^A-Za-z0-9_]/g, '_')}`,
      name: code,
      errorCode: code,
    })
    modeling.updateModdleProperties(props.element, definitions, {
      rootElements: [...rootElements, error],
    })
  }
  modeling.updateModdleProperties(props.element, definition, { errorRef: error })
}
</script>

<template>
  <div v-if="kind" class="property-block">
    <strong>{{ kind === 'timer' ? 'Hẹn giờ' : 'Lỗi nghiệp vụ' }}</strong>
    <el-form label-position="top" size="small">
      <template v-if="kind === 'timer'">
        <el-form-item label="Kiểu">
          <el-select v-model="timer.kind" @change="applyTimer">
            <el-option label="Sau một khoảng thời gian" value="timeDuration" />
            <el-option label="Vào một thời điểm" value="timeDate" />
            <el-option label="Lặp lại theo chu kỳ" value="timeCycle" />
          </el-select>
        </el-form-item>
        <el-form-item label="Giá trị (ISO-8601 hoặc ${biến})">
          <el-input
            v-model="timer.value"
            :placeholder="
              timer.kind === 'timeDuration'
                ? 'P3D'
                : timer.kind === 'timeDate'
                  ? '2026-12-31T17:00:00'
                  : 'R3/PT10M'
            "
            @blur="applyTimer"
          />
        </el-form-item>
      </template>
      <el-form-item v-else label="Mã lỗi">
        <el-input v-model="errorCode" placeholder="PAYMENT_FAILED" @blur="applyError" />
      </el-form-item>
    </el-form>
    <ul v-if="warnings.length" class="property-warnings">
      <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
    </ul>
  </div>
</template>
