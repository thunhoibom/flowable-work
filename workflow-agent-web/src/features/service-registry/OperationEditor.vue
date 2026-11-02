<script setup lang="ts">
import { computed } from 'vue'
import { pathRequirement } from './parameterDraft'
import {
  apiKeyLocations,
  authModes,
  authTypes,
  bodyPresences,
  callStyles,
  contentTypes,
  httpMethods,
  outputSources,
  paginationModes,
  parameterLocations,
  parameterTypes,
  partKinds,
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
const showsOperationSecret = computed(
  () => draft.value.authMode !== 'INHERIT' && draft.value.authType !== 'BEARER_VARIABLE',
)
const secretLabel = computed(() => {
  switch (draft.value.authType) {
    case 'BASIC':
      return 'Username:password'
    case 'OAUTH2_CLIENT':
      return 'Client secret'
    case 'MTLS':
      return 'Khóa riêng PEM'
    case 'HMAC':
      return 'Khóa ký'
    case 'AWS_SIG_V4':
      return 'Secret key'
    default:
      return 'Khóa'
  }
})
const effectiveAuthType = computed(() =>
  draft.value.authMode === 'INHERIT' ? props.sharedAuthType || 'NONE' : draft.value.authType,
)

const emit = defineEmits<{ save: [] }>()

function addInput() {
  draft.value.inputs.push({ name: '', type: 'STRING', required: false, location: 'QUERY', partKind: 'TEXT' })
}

function addStaticHeader() {
  draft.value.staticHeaders.push({ name: '', value: '' })
}

function addOutput() {
  draft.value.outputs.push({ name: '', type: 'STRING', jsonField: '', source: 'JSON_FIELD', headerName: '' })
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
        <el-form-item label="Kiểu nội dung">
          <el-select v-model="draft.contentType" data-field="content-type">
            <el-option v-for="item in contentTypes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft.contentType === 'RAW'" label="Media type">
          <el-input v-model="draft.rawMediaType" data-field="raw-media-type" placeholder="text/plain" />
        </el-form-item>
        <el-form-item label="Gửi body">
          <el-select v-model="draft.bodyPresence" data-field="body-presence">
            <el-option v-for="item in bodyPresences" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="draft.bodyPresence === 'CONTENT' && (draft.contentType === 'JSON' || draft.contentType === 'RAW')"
          label="Mẫu nội dung"
        >
          <el-input v-model="draft.bodyTemplate" data-field="body-template" type="textarea" :rows="4" />
        </el-form-item>
        <div v-for="(header, index) in draft.staticHeaders" :key="`header-${index}`" class="parameter-row">
          <el-input v-model="header.name" data-field="static-header-name" placeholder="Tên header" />
          <el-input v-model="header.value" data-field="static-header-value" placeholder="Giá trị" />
          <el-button @click="draft.staticHeaders.splice(index, 1)">Xóa</el-button>
        </div>
        <el-button @click="addStaticHeader">Thêm header tĩnh</el-button>
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
          <el-form-item v-if="draft.authType === 'BEARER_VARIABLE'" label="Biến Bearer">
            <el-input v-model="draft.bearerVariable" data-field="bearer-variable" placeholder="accessToken" />
          </el-form-item>
          <template v-if="draft.authType === 'OAUTH2_CLIENT'">
            <el-form-item label="URL token">
              <el-input v-model="draft.tokenUrl" data-field="token-url" placeholder="https://auth.example/token" />
            </el-form-item>
            <el-form-item label="Client id">
              <el-input v-model="draft.clientId" data-field="client-id" />
            </el-form-item>
            <el-form-item label="Scope">
              <el-input v-model="draft.scopes" data-field="scopes" placeholder="read, write" />
            </el-form-item>
          </template>
          <el-form-item v-if="draft.authType === 'MTLS'" label="Chứng chỉ PEM">
            <el-input v-model="draft.certificate" data-field="certificate" type="textarea" :rows="4" />
          </el-form-item>
          <el-form-item v-if="draft.authType === 'HMAC'" label="Header chữ ký">
            <el-input v-model="draft.signatureHeader" data-field="signature-header" placeholder="X-Signature" />
          </el-form-item>
          <template v-if="draft.authType === 'AWS_SIG_V4'">
            <el-form-item label="Access key">
              <el-input v-model="draft.accessKey" data-field="access-key" />
            </el-form-item>
            <el-form-item label="Region">
              <el-input v-model="draft.region" data-field="aws-region" placeholder="us-east-1" />
            </el-form-item>
            <el-form-item label="Service">
              <el-input v-model="draft.awsService" data-field="aws-service" placeholder="s3" />
            </el-form-item>
          </template>
          <template v-if="showsApiKeyHeader">
            <el-form-item label="Vị trí API key">
              <el-select v-model="draft.apiKeyLocation" data-field="api-key-location">
                <el-option
                  v-for="item in apiKeyLocations"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="draft.apiKeyLocation === 'QUERY' ? 'Tên query' : 'Header API Key'">
              <el-input v-model="draft.apiKeyHeader" data-field="api-key-header" placeholder="X-Api-Key" />
            </el-form-item>
          </template>
        </template>
        <el-form-item v-if="showsOperationSecret" :label="secretLabel">
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
        <el-select v-if="draft.contentType === 'MULTIPART' && row.location === 'BODY'" v-model="row.partKind">
          <el-option v-for="kind in partKinds" :key="kind.value" :label="kind.label" :value="kind.value" />
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
        <el-select v-model="row.source">
          <el-option v-for="source in outputSources" :key="source.value" :label="source.label" :value="source.value" />
        </el-select>
        <el-input
          v-if="(row.source || 'JSON_FIELD') === 'JSON_FIELD'"
          v-model="row.jsonField"
          placeholder="Trường JSON"
        />
        <el-input
          v-if="row.source === 'RESPONSE_HEADER'"
          v-model="row.headerName"
          placeholder="Tên header"
        />
        <el-button @click="draft.outputs.splice(index, 1)">Xóa</el-button>
      </div>
      <el-button @click="addOutput">Thêm đầu ra</el-button>
    </section>
    <section>
      <h3>Nhiều bước</h3>
      <el-form label-position="top">
        <el-form-item label="Phân trang">
          <el-select v-model="draft.pagination" data-field="pagination">
            <el-option v-for="item in paginationModes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft.pagination !== 'NONE'" label="Trường JSON trang sau">
          <el-input v-model="draft.nextField" data-field="next-field" placeholder="next" />
        </el-form-item>
        <el-form-item v-if="draft.pagination === 'NEXT_TOKEN'" label="Tham số nhận token">
          <el-input v-model="draft.nextTokenParameter" data-field="next-token" placeholder="pageToken" />
        </el-form-item>
        <el-form-item v-if="draft.pagination !== 'NONE'" label="Số trang tối đa">
          <el-input v-model.number="draft.maxPages" data-field="max-pages" />
        </el-form-item>
        <el-form-item label="Kiểu gọi">
          <el-select v-model="draft.callStyle" data-field="call-style">
            <el-option v-for="item in callStyles" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <template v-if="draft.callStyle === 'CALLBACK'">
          <el-form-item label="Đầu ra mã đối soát">
            <el-input v-model="draft.correlationOutput" data-field="correlation-output" placeholder="requestId" />
          </el-form-item>
          <el-form-item label="Thời gian chờ webhook (ms)">
            <el-input v-model.number="draft.waitTimeoutMillis" data-field="wait-timeout" />
          </el-form-item>
          <p>Callback tới POST /service-callbacks/{mã đối soát}. Hết hạn thành SERVICE_UNAVAILABLE.</p>
        </template>
      </el-form>
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
