<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import BpmnViewer from 'bpmn-js/lib/NavigatedViewer'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import type { BpmnModelerInstance, Canvas } from '@/bpmn/modeler-types'
import { formatDateTime, formatDuration } from '@/utils/format'
import type { ActivityDetail, ProcessDiagramData } from '../types'

const props = defineProps<{ data?: ProcessDiagramData }>()
const host = ref<HTMLDivElement>()
const viewer = shallowRef<BpmnModelerInstance>()
const hover = ref<{ detail: ActivityDetail; x: number; y: number }>()
let renderVersion = 0
let canvasResizeObserver: ResizeObserver | undefined

async function render() {
  const version = ++renderVersion
  if (!host.value || !props.data?.bpmnXml) return
  canvasResizeObserver?.disconnect()
  viewer.value?.destroy()
  const nextViewer = new BpmnViewer({ container: host.value }) as BpmnModelerInstance
  viewer.value = nextViewer
  await nextViewer.importXML(props.data.bpmnXml)
  if (version !== renderVersion || viewer.value !== nextViewer) return
  await nextTick()
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  if (version !== renderVersion || viewer.value !== nextViewer) return
  const canvas = nextViewer.get('canvas') as Canvas
  canvasResizeObserver = new ResizeObserver(() => canvas.resized())
  canvasResizeObserver.observe(host.value)
  canvas.resized()
  props.data.completedActivityIds.forEach((id) => canvas.addMarker(id, 'activity-completed'))
  props.data.activeActivityIds.forEach((id) => canvas.addMarker(id, 'activity-active'))
  props.data.highlightedFlows.forEach((id) => canvas.addMarker(id, 'flow-completed'))
  canvas.zoom('fit-viewport', 'auto')
  viewer.value.on('element.hover', (event) => {
    const elementId = event.element?.id
    if (!elementId) return
    const detail = props.data?.activityDetails[elementId]
    if (!detail) return
    const rect = host.value?.getBoundingClientRect()
    hover.value = {
      detail,
      x: Math.min(
        (event.originalEvent?.clientX || 0) - (rect?.left || 0) + 12,
        (rect?.width || 400) - 290,
      ),
      y: Math.max((event.originalEvent?.clientY || 0) - (rect?.top || 0) + 12, 8),
    }
  })
  viewer.value.on('element.out', () => (hover.value = undefined))
}

watch(() => props.data, render, { immediate: true })
onMounted(render)
onBeforeUnmount(() => {
  renderVersion++
  canvasResizeObserver?.disconnect()
  viewer.value?.destroy()
})
</script>

<template>
  <div class="tracking-canvas-wrap">
    <div ref="host" class="tracking-canvas" />
    <div
      v-if="hover"
      class="activity-popover"
      :style="{ left: `${hover.x}px`, top: `${hover.y}px` }"
    >
      <strong>{{ hover.detail.activityName || hover.detail.activityId }}</strong>
      <dl>
        <dt>Loại</dt>
        <dd>{{ hover.detail.activityType }}</dd>
        <dt>Người xử lý</dt>
        <dd>{{ hover.detail.assignee || '-' }}</dd>
        <dt>Người ứng viên</dt>
        <dd>{{ hover.detail.candidateUsers?.join('、') || '-' }}</dd>
        <dt>Bắt đầu</dt>
        <dd>{{ formatDateTime(hover.detail.startTime) }}</dd>
        <dt>Kết thúc</dt>
        <dd>{{ formatDateTime(hover.detail.endTime) }}</dd>
        <dt>Thời lượng</dt>
        <dd>{{ formatDuration(hover.detail.durationInMillis) }}</dd>
      </dl>
      <p v-if="hover.detail.comment">{{ hover.detail.comment }}</p>
    </div>
    <div class="diagram-legend">
      <span class="done-dot" />Đã hoàn thành<span class="active-dot" />Nút hiện tại<span
        class="pending-dot"
      />Chưa tới
    </div>
  </div>
</template>
