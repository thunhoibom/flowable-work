<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BpmnElement, BpmnModelerInstance, Modeling } from '@/bpmn/modeler-types'
import { hasTimerBoundary, waitTaskWarnings } from '@/bpmn/waitTaskModel'

interface HostShape extends BpmnElement {
  x?: number
  y?: number
  width?: number
  height?: number
  attachers?: BpmnElement[]
}
interface ShapeFactory {
  createShape(attrs: { type: string; eventDefinitionType?: string }): BpmnElement
}
interface ShapeModeling extends Modeling {
  createShape(
    shape: BpmnElement,
    position: { x: number; y: number },
    target: BpmnElement,
    hints?: { attach?: boolean },
  ): BpmnElement
}

const props = defineProps<{ element: BpmnElement; modeler: BpmnModelerInstance }>()

const asyncLeave = ref(readAsyncLeave())
// Bumped after changes bpmn-js makes outside Vue's reactivity (attaching a boundary event).
const revision = ref(0)
const host = computed(() => props.element as HostShape)
const hasTimeout = computed(() => revision.value >= 0 && hasTimerBoundary(host.value))
const warnings = computed(() =>
  waitTaskWarnings({ asyncLeave: asyncLeave.value, hasTimeout: hasTimeout.value }),
)

watch(
  () => props.element,
  () => {
    asyncLeave.value = readAsyncLeave()
    revision.value++
  },
)

function readAsyncLeave() {
  return props.element.businessObject?.asyncLeave === true
}

function applyAsyncLeave(value: boolean) {
  asyncLeave.value = value
  const modeling = props.modeler.get('modeling') as Modeling
  modeling.updateProperties(props.element, { asyncLeave: value ? true : undefined })
}

function addTimeout() {
  const factory = props.modeler.get('elementFactory') as ShapeFactory
  const modeling = props.modeler.get('modeling') as ShapeModeling
  const shape = host.value
  const boundary = factory.createShape({
    type: 'bpmn:BoundaryEvent',
    eventDefinitionType: 'bpmn:TimerEventDefinition',
  })
  modeling.createShape(
    boundary,
    { x: (shape.x ?? 0) + (shape.width ?? 100) - 18, y: (shape.y ?? 0) + (shape.height ?? 80) },
    shape,
    { attach: true },
  )
  revision.value++
}
</script>

<template>
  <div class="property-block">
    <strong>Bước chờ</strong>
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
