<script setup lang="ts">
import { computed } from 'vue'
import { pathRequirement } from './parameterDraft'
import {
  authModes,
  authTypes,
  httpMethods,
  parameterLocations,
  parameterTypes,
  type OperationDraft,
} from './operationDraft'

const draft = defineModel<OperationDraft>({ required: true })

const props = defineProps<{
  operationKeyLocked: boolean
  secretPresent: boolean
  saving: boolean
  sharedAuthType?: string
}>()

// The header is an operation credential. Inherited and disabled auth do not send it.
const showsApiKeyHeader = computed(
  () => draft.value.authMode === 'OVERRIDE' && draft.value.authType === 'API_KEY',
)
const showsOperationSecret = computed(() => draft.value.authMode !== 'INHERIT')
const effectiveAuthType = computed(() =>
  draft.value.authMode === 'INHERIT' ? props.sharedAuthType || 'NONE' : draft.value.authType,
)

const emit = defineEmits<{ save: [] }>()

function addInput() {
  draft.value.inputs.push({ name: '', type: 'STRING', required: false, location: 'QUERY' })
}

function addOutput() {
  draft.value.outputs.push({ name: '', type: 'STRING', jsonField: '' })
}

function addErrorRule() {
  draft.value.errorRules.push({ patterns: '', bpmnErrorCode: 'HTTP_ERROR' })
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
        <el-form-item label="Thời gian kết nối (mili giây)">
          <el-input-number v-model="draft.connectTimeoutMillis" data-field="connect-timeout" :min="1" />
        </el-form-item>
        <el-form-item label="Thời gian chờ đọc (mili giây)">
          <el-input-number v-model="draft.timeoutMillis" :min="1" />
        </el-form-item>
      </el-form>
    </section>
    <section>
      <h3>Xác thực</h3>
      <el-form label-position="top">
        <el-form-item label="Cách dùng xác thực">
          <el-select v-model="draft.authMode" data-field="auth-mode">
            <el-option
              v-for="mode in authModes"
              :key="mode.value"
              :label="mode.label"
              :value="mode.value"
            />
          </el-select>
        </el-form-item>
        <p v-if="draft.authMode === 'INHERIT'" class="auth-note">
          Thao tác này dùng xác thực {{ effectiveAuthType }} của dịch vụ.
        </p>
        <template v-if="draft.authMode === 'OVERRIDE'">
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
          <el-form-item v-if="showsApiKeyHeader" label="Header API Key">
            <el-input v-model="draft.apiKeyHeader" data-field="api-key-header" placeholder="X-Api-Key" />
          </el-form-item>
        </template>
        <el-form-item v-if="showsOperationSecret" label="Khóa">
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
        <div v-for="(rule, index) in draft.errorRules" :key="`error-${index}`" class="parameter-row">
          <el-input v-model="rule.patterns" data-field="error-patterns" placeholder="409, 5xx, 400-499" />
          <el-input v-model="rule.bpmnErrorCode" data-field="error-code" placeholder="Mã lỗi" />
          <el-button @click="draft.errorRules.splice(index, 1)">Xóa</el-button>
        </div>
        <el-button @click="addErrorRule">Thêm luật lỗi</el-button>
        <el-form-item label="Trạng thái vẫn coi là thành công">
          <el-input v-model="draft.successStatuses" data-field="success-statuses" placeholder="404" />
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
