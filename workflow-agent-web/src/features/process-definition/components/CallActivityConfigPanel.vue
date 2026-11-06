<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import type {
  BpmnElement,
  BpmnModelerInstance,
  Moddle,
  ModdleElement,
  Modeling,
} from '@/bpmn/modeler-types'
import { formatVersion } from '@/utils/format'
import { definitionApi } from '../api'
import {
  callActivityWarnings,
  readCallActivity,
  suggestedInRows,
  toParameters,
  type CallActivityForm,
  type IoRow,
} from '../callActivityPanel'
import type { ProcessDefinition } from '../types'

const props = defineProps<{
  element: BpmnElement
  modeler: BpmnModelerInstance
  /** Key of the process being edited. It cannot call itself. */
  currentKey: string
}>()

const router = useRouter()
const form = reactive<CallActivityForm>(readCallActivity(props.element.businessObject))
const published = ref<ProcessDefinition[]>([])
const loading = ref(false)
const suggesting = ref(false)
const warnings = computed(() => callActivityWarnings(form))
const parallelMultiInstance = computed(() => {
  const loop = props.element.businessObject?.loopCharacteristics
  return loop?.$type === 'bpmn:MultiInstanceLoopCharacteristics' && loop.isSequential !== true
})
const options = computed(() =>
  published.value
    .filter((item) => item.active && item.processDefinitionKey !== props.currentKey)
    .sort((left, right) =>
      left.processDefinitionName.localeCompare(right.processDefinitionName, 'vi'),
    ),
)
const selected = computed(() =>
  published.value.find((item) => item.processDefinitionKey === form.calledElement),
)

watch(
  () => props.element,
  (element) => Object.assign(form, readCallActivity(element.businessObject)),
)

onMounted(async () => {
  loading.value = true
  try {
    published.value = await definitionApi.listProcesses()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
})

function apply() {
  const object = props.element.businessObject
  if (!object) return
  const modeling = props.modeler.get('modeling') as Modeling
  const moddle = props.modeler.get('moddle') as Moddle
  const kept = (object.extensionElements?.values ?? []).filter(
    (item: ModdleElement) => item.$type !== 'flowable:In' && item.$type !== 'flowable:Out',
  )
  const values = [
    ...kept,
    ...toParameters(form.inRows).map((parameter) => moddle.create('flowable:In', { ...parameter })),
    ...toParameters(form.outRows).map((parameter) =>
      moddle.create('flowable:Out', { ...parameter }),
    ),
  ]
  modeling.updateModdleProperties(props.element, object, {
    calledElement: form.calledElement.trim() || undefined,
    calledElementType: undefined,
    inheritBusinessKey: form.businessKeyMode === 'inherit' ? true : undefined,
    businessKey:
      form.businessKeyMode === 'expression' ? form.businessKey.trim() || undefined : undefined,
    idVariableName: form.idVariableName.trim() || undefined,
    // Parallel children finishing at once would otherwise contend on the parent execution.
    completeAsync: parallelMultiInstance.value ? true : undefined,
    extensionElements: values.length
      ? moddle.create('bpmn:ExtensionElements', { values })
      : undefined,
  })
}

function updateRows(direction: 'inRows' | 'outRows', index: number, patch: Partial<IoRow>) {
  form[direction] = form[direction].map((row, rowIndex) =>
    rowIndex === index ? { ...row, ...patch } : row,
  )
}

function addRow(direction: 'inRows' | 'outRows') {
  form[direction] = [...form[direction], { source: '', target: '' }]
}

function removeRow(direction: 'inRows' | 'outRows', index: number) {
  form[direction] = form[direction].filter((_, rowIndex) => rowIndex !== index)
  apply()
}

async function suggestInputs() {
  if (!form.calledElement) return
  suggesting.value = true
  try {
    const { fields } = await definitionApi.startInputs(form.calledElement)
    const rows = suggestedInRows(fields, form.inRows)
    if (!rows.length) {
      ElMessage.info('Quy trình được gọi không cần thêm dữ liệu bắt buộc')
      return
    }
    form.inRows = [...form.inRows, ...rows]
    apply()
    ElMessage.success(`Đã thêm ${rows.length} dòng dữ liệu truyền vào`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    suggesting.value = false
  }
}

function openCalled() {
  if (!form.calledElement) return
  const target = router.resolve({ name: 'process-designer', query: { key: form.calledElement } })
  window.open(target.href, '_blank', 'noopener')
}
</script>

<template>
  <div class="property-block">
    <strong>Gọi quy trình khác</strong>
    <p class="property-hint">
      Luôn chạy bản đang phát hành của quy trình được gọi. Bản nháp của nó không bao giờ được gọi.
    </p>
    <el-form label-position="top" size="small">
      <el-form-item label="Quy trình được gọi">
        <el-select
          v-model="form.calledElement"
          filterable
          :loading="loading"
          placeholder="Chọn quy trình đang phát hành"
          @change="apply"
        >
          <el-option
            v-for="item in options"
            :key="item.processDefinitionKey"
            :label="`${item.processDefinitionName} · ${item.processDefinitionKey}`"
            :value="item.processDefinitionKey"
          >
            <span>{{ item.processDefinitionName }}</span>
            <small class="option-meta">
              {{ item.processDefinitionKey }} · {{ formatVersion(item.version) }}
            </small>
          </el-option>
        </el-select>
        <p v-if="form.calledElement && !selected?.active && !loading" class="property-warnings">
          Quy trình này hiện không phát hành. Phát hành sẽ bị từ chối.
        </p>
        <div class="call-activity-actions">
          <el-button :disabled="!form.calledElement" :loading="suggesting" @click="suggestInputs">
            Gợi ý từ quy trình con
          </el-button>
          <el-button :disabled="!form.calledElement" @click="openCalled"
            >Mở quy trình được gọi</el-button
          >
        </div>
      </el-form-item>

      <el-form-item label="Dữ liệu truyền vào">
        <div v-for="(row, index) in form.inRows" :key="`in-${index}`" class="io-row">
          <el-input
            :model-value="row.source"
            placeholder="Biến, hoặc ${biểu thức}"
            @update:model-value="updateRows('inRows', index, { source: $event })"
            @blur="apply"
          />
          <el-input
            :model-value="row.target"
            placeholder="Tên trong quy trình con"
            @update:model-value="updateRows('inRows', index, { target: $event })"
            @blur="apply"
          />
          <el-button text @click="removeRow('inRows', index)">Xóa</el-button>
        </div>
        <el-button size="small" @click="addRow('inRows')">Thêm dòng</el-button>
      </el-form-item>

      <el-form-item label="Dữ liệu nhận về">
        <div v-for="(row, index) in form.outRows" :key="`out-${index}`" class="io-row">
          <el-input
            :model-value="row.source"
            placeholder="Biến của quy trình con"
            @update:model-value="updateRows('outRows', index, { source: $event })"
            @blur="apply"
          />
          <el-input
            :model-value="row.target"
            placeholder="Lưu vào biến"
            @update:model-value="updateRows('outRows', index, { target: $event })"
            @blur="apply"
          />
          <el-button text @click="removeRow('outRows', index)">Xóa</el-button>
        </div>
        <el-button size="small" @click="addRow('outRows')">Thêm dòng</el-button>
      </el-form-item>

      <el-form-item label="Business key của quy trình con">
        <el-radio-group v-model="form.businessKeyMode" @change="apply">
          <el-radio value="none">Không có</el-radio>
          <el-radio value="inherit">Dùng của quy trình này</el-radio>
          <el-radio value="expression">Biểu thức</el-radio>
        </el-radio-group>
        <el-input
          v-if="form.businessKeyMode === 'expression'"
          v-model="form.businessKey"
          placeholder="${orderId}"
          @blur="apply"
        />
      </el-form-item>

      <el-form-item label="Lưu id instance con vào biến (tuỳ chọn)">
        <el-input v-model="form.idVariableName" placeholder="paymentInstanceId" @blur="apply" />
      </el-form-item>
    </el-form>
    <p v-if="parallelMultiInstance" class="property-hint">
      Lặp song song: các quy trình con hoàn tất bất đồng bộ để không tranh chấp với nhau.
    </p>
    <ul v-if="warnings.length" class="property-warnings">
      <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
    </ul>
  </div>
</template>

<style scoped>
.io-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
}

.call-activity-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.option-meta {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
