<script setup lang="ts">
import { pathRequirement } from './parameterDraft'
import {
  authTypes,
  httpMethods,
  parameterLocations,
  parameterTypes,
  type OperationDraft,
} from './operationDraft'

const draft = defineModel<OperationDraft>({ required: true })

defineProps<{
  operationKeyLocked: boolean
  secretPresent: boolean
  saving: boolean
}>()

const emit = defineEmits<{ save: [] }>()

function addInput() {
  draft.value.inputs.push({ name: '', type: 'STRING', required: false, location: 'QUERY' })
}

function addOutput() {
  draft.value.outputs.push({ name: '', type: 'STRING', jsonField: '' })
}

function setLocation(index: number, location: string) {
  const row = draft.value.inputs[index]
  if (!row) return
  const next = pathRequirement(location, row.required)
  row.location = location
  row.required = next.required
}
</script>

<template>
  <div class="operation-editor">
    <section>
      <h3>Yêu cầu</h3>
      <el-form label-position="top">
        <el-form-item label="Mã thao tác">
          <el-input
            v-model="draft.operationKey"
            data-field="operation-key"
            :disabled="operationKeyLocked"
          />
        </el-form-item>
        <el-form-item label="Phương thức">
          <el-select v-model="draft.method">
            <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
          </el-select>
        </el-form-item>
        <el-form-item label="Mẫu địa chỉ">
          <el-input
            v-model="draft.url"
            data-field="url"
            placeholder="https://crm.example/leads/${leadId}"
          />
        </el-form-item>
        <el-form-item label="Mẫu nội dung">
          <el-input v-model="draft.bodyTemplate" data-field="body-template" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="Thời gian chờ (mili giây)">
          <el-input-number v-model="draft.timeoutMillis" :min="1" />
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Xác thực</h3>
      <el-form label-position="top">
        <el-form-item label="Xác thực">
          <el-select v-model="draft.authType">
            <el-option
              v-for="auth in authTypes"
              :key="auth.value"
              :label="auth.label"
              :value="auth.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft.authType === 'API_KEY'" label="Header API Key">
          <el-input v-model="draft.apiKeyHeader" data-field="api-key-header" placeholder="X-Api-Key" />
        </el-form-item>
        <el-form-item label="Khóa">
          <el-input
            v-model="draft.secret"
            type="password"
            show-password
            placeholder="Để trống để giữ khóa đã lưu"
          />
          <p v-if="secretPresent">Đã lưu khóa. Để trống để giữ khóa này.</p>
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Lỗi</h3>
      <el-form label-position="top">
        <el-form-item label="Mã trạng thái lỗi">
          <el-input v-model="draft.errorStatusCodes" placeholder="409,5XX" />
        </el-form-item>
        <el-form-item label="Mã lỗi">
          <el-input v-model="draft.bpmnErrorCode" data-field="bpmn-error-code" />
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Đầu vào</h3>
      <div v-for="(row, index) in draft.inputs" :key="`input-${index}`" class="parameter-row">
        <el-input v-model="row.name" placeholder="Tên tham số" />
        <el-select v-model="row.type">
          <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-select
          :model-value="row.location"
          @update:model-value="setLocation(index, String($event || ''))"
        >
          <el-option
            v-for="location in parameterLocations"
            :key="location"
            :label="location"
            :value="location"
          />
        </el-select>
        <el-checkbox
          :model-value="row.required"
          :disabled="pathRequirement(row.location, row.required).locked"
          @update:model-value="row.required = Boolean($event)"
        >
          Bắt buộc
        </el-checkbox>
        <el-button @click="draft.inputs.splice(index, 1)">Xóa</el-button>
      </div>
      <el-button @click="addInput">Thêm đầu vào</el-button>
    </section>
    <section>
      <h3>Đầu ra</h3>
      <div v-for="(row, index) in draft.outputs" :key="`output-${index}`" class="parameter-row">
        <el-input v-model="row.name" placeholder="Tên tham số" />
        <el-select v-model="row.type">
          <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input v-model="row.jsonField" placeholder="Trường JSON" />
        <el-button @click="draft.outputs.splice(index, 1)">Xóa</el-button>
      </div>
      <el-button @click="addOutput">Thêm đầu ra</el-button>
    </section>
    <el-button data-action="save-operation" :loading="saving" @click="emit('save')">
      Lưu thao tác
    </el-button>
  </div>
</template>

<style scoped>
.operation-editor {
  display: grid;
  gap: 20px;
}

.operation-editor section {
  display: grid;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border-soft);
}

.operation-editor h3 {
  margin: 0;
  font-size: 17px;
}

.operation-editor p {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: 14px;
}

.operation-editor :deep(.el-form-item__label) {
  font-size: 14px;
}

.parameter-row {
  display: grid;
  grid-template-columns: minmax(140px, 1.4fr) minmax(110px, 1fr) minmax(110px, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}
</style>
