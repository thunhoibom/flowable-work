<script setup lang="ts">
import type { ScriptInputRow } from '../scriptTaskPanel'

const props = defineProps<{
  script: string
  inputRows: ScriptInputRow[]
  resultVariable: string
}>()

const emit = defineEmits<{
  'update:script': [value: string]
  'update:inputRows': [value: ScriptInputRow[]]
  'update:resultVariable': [value: string]
  apply: []
}>()

function updateRow(index: number, patch: Partial<ScriptInputRow>) {
  emit(
    'update:inputRows',
    props.inputRows.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row)),
  )
}

function addRow() {
  emit('update:inputRows', [...props.inputRows, { target: '', source: '' }])
}

function removeRow(index: number) {
  emit(
    'update:inputRows',
    props.inputRows.filter((_, rowIndex) => rowIndex !== index),
  )
  emit('apply')
}
</script>

<template>
  <div class="property-block">
    <strong>Script</strong>
    <el-form label-position="top" size="small">
      <el-form-item label="JavaScript">
        <el-input
          :model-value="script"
          type="textarea"
          :rows="10"
          class="script-editor"
          placeholder="amount * qty"
          @update:model-value="emit('update:script', $event)"
          @blur="emit('apply')"
        />
        <p class="script-hint">
          Giá trị của dòng cuối là kết quả. Script chỉ thấy các đầu vào bên dưới, không gọi được
          Java, và chạy trên Rhino nên chỉ hỗ trợ một phần ES6.
        </p>
      </el-form-item>
      <el-form-item label="Đầu vào">
        <div v-for="(row, index) in inputRows" :key="index" class="script-input-row">
          <el-input
            :model-value="row.target"
            placeholder="Tên trong script"
            @update:model-value="updateRow(index, { target: $event })"
            @blur="emit('apply')"
          />
          <el-input
            :model-value="row.source"
            placeholder="Biến, hoặc ${biểu thức}"
            @update:model-value="updateRow(index, { source: $event })"
            @blur="emit('apply')"
          />
          <el-button text @click="removeRow(index)">Xóa</el-button>
        </div>
        <el-button size="small" @click="addRow">Thêm đầu vào</el-button>
      </el-form-item>
      <el-form-item label="Biến kết quả">
        <el-input
          :model-value="resultVariable"
          placeholder="total"
          @update:model-value="emit('update:resultVariable', $event)"
          @blur="emit('apply')"
        />
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.script-editor :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.script-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}

.script-input-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
}
</style>
