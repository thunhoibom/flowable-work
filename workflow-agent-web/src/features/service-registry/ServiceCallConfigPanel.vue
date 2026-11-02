<script setup lang="ts">
import type { CatalogOperation, ServiceView } from './api'
import type { ServiceInputRow, ServiceOutputRow } from './serviceCallMapping'

const props = defineProps<{
  serviceKey: string
  operationKey: string
  services: ServiceView[]
  inputRows: ServiceInputRow[]
  outputRows: ServiceOutputRow[]
}>()

const emit = defineEmits<{
  'update:serviceKey': [value: string]
  'update:operationKey': [value: string]
  'update:inputRows': [value: ServiceInputRow[]]
  'update:outputRows': [value: ServiceOutputRow[]]
  apply: []
}>()

function operations(): CatalogOperation[] {
  return (
    props.services.find((service) => service.serviceKey === props.serviceKey)?.operations || []
  ).filter((operation) => operation.publishedUrl)
}

function selectedOperation(): CatalogOperation | undefined {
  return operations().find((operation) => operation.operationKey === props.operationKey)
}

function publishedStep() {
  return selectedOperation()?.publishedSteps || selectedOperation()?.steps
}

function updateInput(index: number, expression: string) {
  emit(
    'update:inputRows',
    props.inputRows.map((row, rowIndex) => (rowIndex === index ? { ...row, expression } : row)),
  )
  emit('apply')
}

function sourceLabel(row: ServiceOutputRow) {
  const source = row.source || 'JSON_FIELD'
  if (source === 'RAW_BODY') return 'Nội dung thô'
  if (source === 'STATUS') return 'Mã trạng thái'
  if (source === 'RESPONSE_HEADER') return `Header ${row.headerName || ''}`.trim()
  return `Trường JSON ${row.jsonField || ''}`.trim()
}

function updateOutput(index: number, variable: string) {
  emit(
    'update:outputRows',
    props.outputRows.map((row, rowIndex) => (rowIndex === index ? { ...row, variable } : row)),
  )
  emit('apply')
}
</script>

<template>
  <div class="property-block">
    <strong>Gọi dịch vụ</strong>
    <el-form label-position="top" size="small">
      <el-form-item label="Dịch vụ">
        <el-select
          :model-value="props.serviceKey"
          filterable
          placeholder="Chọn dịch vụ"
          @update:model-value="emit('update:serviceKey', String($event || ''))"
          @change="emit('apply')"
        >
          <el-option
            v-for="service in props.services"
            :key="service.serviceKey"
            :label="`${service.name} · ${service.serviceKey}`"
            :value="service.serviceKey"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="Thao tác">
        <el-select
          :model-value="props.operationKey"
          filterable
          placeholder="Chọn thao tác"
          @update:model-value="emit('update:operationKey', String($event || ''))"
          @change="emit('apply')"
        >
          <el-option
            v-for="operation in operations()"
            :key="operation.operationKey"
            :label="`${operation.method} · ${operation.operationKey}`"
            :value="operation.operationKey"
          />
        </el-select>
      </el-form-item>
      <p>{{ selectedOperation()?.publishedMethod }}</p>
      <p>{{ selectedOperation()?.publishedUrl }}</p>
      <p>{{ selectedOperation()?.publishedBpmnErrorCode }}</p>
      <p v-if="selectedOperation()">
        Kiểu nội dung: {{ selectedOperation()?.publishedContentType || selectedOperation()?.contentType || 'JSON' }}
      </p>
      <p v-if="publishedStep()?.pagination && publishedStep()?.pagination !== 'NONE'">
        Phân trang: {{ publishedStep()?.pagination }} · tối đa {{ publishedStep()?.maxPages || 10 }} trang
      </p>
      <p v-if="publishedStep()?.callStyle === 'CALLBACK'">
        Chờ webhook · hết hạn {{ publishedStep()?.waitTimeoutMillis }} ms
      </p>
      <el-form-item v-for="(row, index) in props.inputRows" :key="row.name" :label="row.name">
        <el-input
          :model-value="row.expression"
          @update:model-value="updateInput(index, String($event ?? ''))"
        />
      </el-form-item>
      <el-form-item v-for="(row, index) in props.outputRows" :key="row.name" :label="row.name">
        <!-- The node only chooses the process variable. The source stays on the published operation. -->
        <p class="output-source">Nguồn: {{ sourceLabel(row) }}</p>
        <el-input
          :model-value="row.variable"
          @update:model-value="updateOutput(index, String($event ?? ''))"
        />
      </el-form-item>
    </el-form>
  </div>
</template>
