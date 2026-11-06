<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { BpmnElement, BpmnModelerInstance, Moddle, Modeling } from '@/bpmn/modeler-types'
import {
  isEventSubProcess,
  multiInstanceWarnings,
  readMultiInstance,
  type MultiInstanceForm,
} from '@/bpmn/subProcessModel'

const props = defineProps<{
  element: BpmnElement
  modeler: BpmnModelerInstance
}>()

const form = reactive<MultiInstanceForm>(readMultiInstance(props.element.businessObject))
const warnings = computed(() => multiInstanceWarnings(form))
const eventTriggered = computed(() => isEventSubProcess(props.element.businessObject))

watch(
  () => props.element,
  (element) => Object.assign(form, readMultiInstance(element.businessObject)),
)

function apply() {
  const modeling = props.modeler.get('modeling') as Modeling
  const moddle = props.modeler.get('moddle') as Moddle
  if (!form.enabled) {
    modeling.updateProperties(props.element, { loopCharacteristics: undefined })
    return
  }
  const loop = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
    isSequential: form.sequential,
    collection: form.collection.trim(),
    elementVariable: form.elementVariable.trim(),
  })
  if (form.completionCondition.trim()) {
    loop.completionCondition = moddle.create('bpmn:FormalExpression', {
      body: form.completionCondition.trim(),
    })
  }
  modeling.updateProperties(props.element, { loopCharacteristics: loop })
}
</script>

<template>
  <div class="property-block">
    <strong>{{ eventTriggered ? 'Quy trình con sự kiện' : 'Quy trình con' }}</strong>
    <p v-if="eventTriggered" class="property-hint">
      Chạy khi có sự kiện ở bước bắt đầu bên trong (lỗi hoặc hẹn giờ). Không nối đường đi vào hay
      ra.
    </p>
    <template v-else>
      <p class="property-hint">
        Nhấn đúp để mở rộng hoặc thu gọn. Quy trình con thu gọn có nút mũi tên để đi xuống sơ đồ bên
        trong.
      </p>
      <el-form label-position="top" size="small">
        <el-form-item>
          <el-checkbox v-model="form.enabled" @change="apply">Lặp theo danh sách</el-checkbox>
        </el-form-item>
        <template v-if="form.enabled">
          <el-form-item label="Cách chạy">
            <el-radio-group v-model="form.sequential" @change="apply">
              <el-radio :value="false">Song song</el-radio>
              <el-radio :value="true">Lần lượt</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="Danh sách">
            <el-input v-model="form.collection" placeholder="${reviewers}" @blur="apply" />
          </el-form-item>
          <el-form-item label="Tên biến cho từng phần tử">
            <el-input v-model="form.elementVariable" placeholder="reviewer" @blur="apply" />
          </el-form-item>
          <el-form-item label="Dừng sớm khi (tuỳ chọn)">
            <el-input
              v-model="form.completionCondition"
              placeholder="${nrOfCompletedInstances >= 2}"
              @blur="apply"
            />
          </el-form-item>
        </template>
      </el-form>
      <ul v-if="warnings.length" class="property-warnings">
        <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
      </ul>
    </template>
  </div>
</template>
