<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import { serviceCatalogApi, type CatalogOperation, type ServiceView } from './api'
import { pathRequirement } from './parameterDraft'

const services = ref<ServiceView[]>([])
const loading = ref(false)
const creating = ref(false)
const saving = ref(false)
const publishing = ref(false)
const serviceForm = reactive({ serviceKey: '', name: '' })
const operationForm = reactive({
  operationKey: '',
  method: 'POST',
  url: '',
  authType: 'NONE',
  apiKeyHeader: '',
  secret: '',
  bodyTemplate: '',
  timeoutMillis: 5000,
  errorStatusCodes: '409,5XX',
  bpmnErrorCode: 'HTTP_ERROR',
})
const inputRows = ref<
  Array<{ name: string; type: string; required: boolean; location: string }>
>([])
const outputRows = ref<Array<{ name: string; type: string; jsonField: string }>>([])

async function refresh() {
  loading.value = true
  try {
    services.value = await serviceCatalogApi.list()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function createService() {
  creating.value = true
  try {
    await serviceCatalogApi.create({
      serviceKey: serviceForm.serviceKey.trim(),
      name: serviceForm.name.trim(),
    })
    await refresh()
    ElMessage.success('Đã lưu dịch vụ')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    creating.value = false
  }
}

async function saveOperation() {
  saving.value = true
  try {
    await serviceCatalogApi.saveOperation(serviceForm.serviceKey.trim(), operationForm.operationKey.trim(), {
      method: operationForm.method,
      url: operationForm.url.trim(),
      authType: operationForm.authType,
      apiKeyHeader: operationForm.apiKeyHeader.trim() || undefined,
      secret: operationForm.secret || undefined,
      bodyTemplate: operationForm.bodyTemplate || undefined,
      timeoutMillis: operationForm.timeoutMillis,
      errorStatusCodes: operationForm.errorStatusCodes
        .split(',')
        .map((code) => code.trim())
        .filter(Boolean),
      bpmnErrorCode: operationForm.bpmnErrorCode.trim() || 'HTTP_ERROR',
      inputParameters: inputRows.value.map((row) => ({
        name: row.name,
        type: row.type,
        required: pathRequirement(row.location, row.required).required,
        location: row.location,
      })),
      outputParameters: outputRows.value.map((row) => ({
        name: row.name,
        type: row.type,
        jsonField: row.jsonField,
      })),
    })
    operationForm.secret = ''
    await refresh()
    ElMessage.success('Đã lưu thao tác')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publishService() {
  publishing.value = true
  try {
    await serviceCatalogApi.publish(serviceForm.serviceKey.trim())
    await refresh()
    ElMessage.success('Đã phát hành. Những lần chạy sau dùng bản này')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishing.value = false
  }
}

function editOperation(serviceKey: string, name: string, operation: CatalogOperation) {
  serviceForm.serviceKey = serviceKey
  serviceForm.name = name
  operationForm.operationKey = operation.operationKey
  operationForm.method = operation.method
  operationForm.url = operation.urlTemplate
  operationForm.authType = operation.authType
  operationForm.apiKeyHeader = operation.apiKeyHeader || ''
  operationForm.secret = ''
  operationForm.bodyTemplate = operation.bodyTemplate || ''
  operationForm.timeoutMillis = operation.timeoutMillis
  operationForm.errorStatusCodes = operation.errorStatusCodes.join(',')
  operationForm.bpmnErrorCode = operation.bpmnErrorCode
  inputRows.value = (operation.inputParameters || []).map((row) => ({
    ...row,
    required: pathRequirement(row.location, row.required).required,
  }))
  outputRows.value = (operation.outputParameters || []).map((row) => ({ ...row }))
}

const parameterTypes = ['STRING', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON']
const parameterLocations = ['PATH', 'QUERY', 'HEADER', 'BODY']

function addInput() {
  inputRows.value.push({ name: '', type: 'STRING', required: false, location: 'QUERY' })
}

function addOutput() {
  outputRows.value.push({ name: '', type: 'STRING', jsonField: '' })
}

function setLocation(
  row: { required: boolean; location: string },
  location: string,
) {
  const next = pathRequirement(location, row.required)
  row.location = location
  row.required = next.required
}

onMounted(refresh)
</script>

<template>
  <section class="service-catalog">
    <header>
      <h2>Dịch vụ</h2>
      <p>Khai báo API bên ngoài. Trong quy trình, bạn chỉ chọn dịch vụ và thao tác.</p>
    </header>
    <el-form label-position="top">
      <el-form-item label="Mã dịch vụ">
        <el-input v-model="serviceForm.serviceKey" placeholder="crm" />
      </el-form-item>
      <el-form-item label="Tên dịch vụ">
        <el-input v-model="serviceForm.name" placeholder="CRM" />
      </el-form-item>
      <el-button type="primary" :loading="creating" @click="createService">Lưu dịch vụ</el-button>
    </el-form>
    <el-form label-position="top">
      <el-form-item label="Mã thao tác">
        <el-input v-model="operationForm.operationKey" placeholder="createLead" />
      </el-form-item>
      <el-form-item label="Phương thức">
        <el-select v-model="operationForm.method">
          <el-option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']" :key="method" :label="method" :value="method" />
        </el-select>
      </el-form-item>
      <el-form-item label="Mẫu địa chỉ">
        <el-input v-model="operationForm.url" placeholder="https://crm.example/leads/${leadId}" />
      </el-form-item>
      <el-form-item label="Mẫu nội dung">
        <el-input v-model="operationForm.bodyTemplate" />
      </el-form-item>
      <el-form-item label="Xác thực">
        <el-select v-model="operationForm.authType">
          <el-option label="Không" value="NONE" />
          <el-option label="Bearer" value="BEARER" />
          <el-option label="Basic" value="BASIC" />
          <el-option label="API Key" value="API_KEY" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="operationForm.authType === 'API_KEY'" label="Header API Key">
        <el-input v-model="operationForm.apiKeyHeader" placeholder="X-Api-Key" />
      </el-form-item>
      <el-form-item label="Khóa">
        <el-input v-model="operationForm.secret" type="password" show-password placeholder="Để trống để giữ khóa đã lưu" />
      </el-form-item>
      <el-form-item label="Thời gian chờ (mili giây)">
        <el-input-number v-model="operationForm.timeoutMillis" :min="1" />
      </el-form-item>
      <el-form-item label="Mã trạng thái lỗi">
        <el-input v-model="operationForm.errorStatusCodes" placeholder="409,5XX" />
      </el-form-item>
      <el-form-item label="Mã lỗi">
        <el-input v-model="operationForm.bpmnErrorCode" />
      </el-form-item>
      <div class="parameter-block">
        <strong>Đầu vào</strong>
        <div v-for="(row, index) in inputRows" :key="`input-${index}`" class="parameter-row">
          <el-input v-model="row.name" placeholder="Tên tham số" />
          <el-select v-model="row.type">
            <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
          </el-select>
          <el-select
            :model-value="row.location"
            @update:model-value="setLocation(row, String($event || ''))"
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
          <el-button @click="inputRows.splice(index, 1)">Xóa</el-button>
        </div>
        <el-button @click="addInput">Thêm đầu vào</el-button>
      </div>
      <div class="parameter-block">
        <strong>Đầu ra</strong>
        <div v-for="(row, index) in outputRows" :key="`output-${index}`" class="parameter-row">
          <el-input v-model="row.name" placeholder="Tên tham số" />
          <el-select v-model="row.type">
            <el-option v-for="type in parameterTypes" :key="type" :label="type" :value="type" />
          </el-select>
          <el-input v-model="row.jsonField" placeholder="jsonField" />
          <el-button @click="outputRows.splice(index, 1)">Xóa</el-button>
        </div>
        <el-button @click="addOutput">Thêm đầu ra</el-button>
      </div>
      <el-button :loading="saving" @click="saveOperation">Lưu thao tác</el-button>
      <el-button type="primary" :loading="publishing" @click="publishService">Phát hành</el-button>
    </el-form>
    <ul>
      <li v-for="service in services" :key="service.serviceKey">
        <button
          v-for="operation in service.operations"
          :key="operation.operationKey"
          type="button"
          :data-operation="operation.operationKey"
          @click="editOperation(service.serviceKey, service.name, operation)"
        >
          {{ operation.method }} {{ operation.operationKey }}
          · {{ operation.publishedUrl || 'Chưa phát hành' }}
          · {{ operation.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}
        </button>
      </li>
    </ul>
    <p v-if="loading">Đang tải</p>
  </section>
</template>

<style scoped>
.service-catalog {
  display: grid;
  gap: 24px;
  max-width: 720px;
}

.parameter-block {
  display: grid;
  gap: 8px;
}

.parameter-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr auto auto;
  gap: 8px;
  align-items: center;
}
</style>
