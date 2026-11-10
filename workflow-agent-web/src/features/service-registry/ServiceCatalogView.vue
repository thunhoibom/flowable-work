<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ListTree, Plus, RefreshCw, Rocket, Search, Server, SquarePen, Trash2 } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import TableTagCell from '@/components/TableTagCell.vue'
import { confirmAction } from '@/utils/confirmation'
import { serviceCatalogApi, type CatalogOperation, type ServiceView } from './api'
import { serviceNeedsPublish } from './catalogStatus'
import OperationEditor from './OperationEditor.vue'
import OperationTable from './OperationTable.vue'
import {
  authTypes,
  blankOperationDraft,
  draftFromOperation,
  isOperationDraftDirty,
  toSaveCommand,
  validateOperationDraft,
  type OperationDraft,
} from './operationDraft'

const services = ref<ServiceView[]>([])
const loading = ref(false)
const creating = ref(false)
const saving = ref(false)
const savingService = ref(false)
const publishing = ref(false)
const createVisible = ref(false)
const editVisible = ref(false)
const editorVisible = ref(false)
/** The operations modal of the service in `selectedKey`. */
const operationsVisible = ref(false)
const query = ref('')
const selectedKey = ref('')
const editingKey = ref<string | null>(null)
const secretPresent = ref(false)
const draft = ref<OperationDraft>(blankOperationDraft())
const baseline = ref<OperationDraft>(blankOperationDraft())
const createForm = reactive({ serviceKey: '', name: '' })
const editForm = reactive({
  serviceKey: '',
  name: '',
  description: '',
  baseUrls: [{ environment: 'default', url: '' }],
  authType: 'NONE',
  apiKeyHeader: '',
  apiKeyLocation: 'HEADER',
  bearerVariable: '',
  tokenUrl: '',
  clientId: '',
  scopes: '',
  certificate: '',
  signatureHeader: '',
  accessKey: '',
  region: '',
  awsService: '',
  secret: '',
})

const selectedService = computed(() =>
  services.value.find((service) => service.serviceKey === selectedKey.value),
)
const dirty = computed(() => isOperationDraftDirty(draft.value, baseline.value))
const filteredServices = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return services.value
  return services.value.filter(
    (service) =>
      service.serviceKey.toLowerCase().includes(needle) ||
      service.name.toLowerCase().includes(needle),
  )
})

type ServiceStatus = 'empty' | 'pending' | 'published'
const serviceStatusLabel: Record<ServiceStatus, string> = {
  empty: 'Chưa có thao tác',
  pending: 'Cần phát hành',
  published: 'Đã phát hành',
}
const serviceStatusTag: Record<ServiceStatus, 'info' | 'warning' | 'success'> = {
  empty: 'info',
  pending: 'warning',
  published: 'success',
}

function serviceStatus(service: ServiceView): ServiceStatus {
  if (!service.operations.length) return 'empty'
  return serviceNeedsPublish(service.operations) ? 'pending' : 'published'
}

function authLabel(service: ServiceView) {
  const type = service.sharedAuth?.type || 'NONE'
  return authTypes.find((auth) => auth.value === type)?.label ?? type
}

function baseUrlLabel(service: ServiceView) {
  const first = service.baseUrls?.[0]
  if (!first) return '—'
  const more = (service.baseUrls?.length ?? 1) - 1
  return `${first.environment}: ${first.url}${more > 0 ? ` (+${more})` : ''}`
}

function loadNew() {
  editingKey.value = null
  secretPresent.value = false
  draft.value = blankOperationDraft()
  baseline.value = blankOperationDraft()
}

function loadOperation(operation: CatalogOperation) {
  editingKey.value = operation.operationKey
  secretPresent.value = operation.secretPresent
  draft.value = draftFromOperation(operation)
  baseline.value = draftFromOperation(operation)
}

async function confirmDiscard() {
  if (!dirty.value) return true
  return confirmAction('Thay đổi thao tác chưa được lưu. Hủy các thay đổi này?', 'Hủy thay đổi', {
    type: 'warning',
    confirmButtonText: 'Hủy thay đổi',
    cancelButtonText: 'Ở lại',
  })
}

async function refresh(prefer?: { serviceKey?: string; operationKey?: string }) {
  loading.value = true
  try {
    services.value = (await serviceCatalogApi.list()).filter((service) => service.serviceKey)
    const preferred = prefer?.serviceKey || selectedKey.value
    const service = services.value.find((item) => preferred && item.serviceKey === preferred)
    if (!service) {
      selectedKey.value = ''
      operationsVisible.value = false
      editorVisible.value = false
      loadNew()
      return
    }
    selectedKey.value = service.serviceKey
    const operation = service.operations.find((item) => item.operationKey === prefer?.operationKey)
    if (operation) loadOperation(operation)
    else if (prefer?.operationKey === undefined && editingKey.value) {
      const current = service.operations.find((item) => item.operationKey === editingKey.value)
      if (current) loadOperation(current)
      else loadNew()
    } else loadNew()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function openOperations(serviceKey: string) {
  selectedKey.value = serviceKey
  editorVisible.value = false
  loadNew()
  operationsVisible.value = true
}

function closeOperations() {
  operationsVisible.value = false
  editorVisible.value = false
  loadNew()
}

async function selectOperation(operationKey: string) {
  if (editorVisible.value && operationKey === editingKey.value) return
  if (!(await confirmDiscard())) return
  const operation = selectedService.value?.operations.find((item) => item.operationKey === operationKey)
  if (!operation) return
  loadOperation(operation)
  editorVisible.value = true
}

async function addOperation() {
  if (editorVisible.value && editingKey.value === null && !dirty.value) return
  if (!(await confirmDiscard())) return
  loadNew()
  editorVisible.value = true
}

async function closeEditor(done?: () => void) {
  if (!(await confirmDiscard())) return
  editorVisible.value = false
  loadNew()
  done?.()
}

async function removeOperation(operationKey: string) {
  const confirmed = await confirmAction(
    `Xóa thao tác “${operationKey}”? Bản đã phát hành vẫn giữ thao tác này cho đến lần phát hành tiếp theo.`,
    'Xóa thao tác',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  const serviceKey = selectedKey.value
  try {
    await serviceCatalogApi.deleteOperation(serviceKey, operationKey)
    if (editingKey.value === operationKey) {
      editorVisible.value = false
      loadNew()
    }
    await refresh({ serviceKey })
    ElMessage.success('Đã xóa thao tác')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function removeService(serviceKey: string) {
  const service = services.value.find((item) => item.serviceKey === serviceKey)
  if (!service?.serviceKey) return
  const confirmed = await confirmAction(
    `Xóa dịch vụ “${service.name}”? Những lần chạy sau sẽ không còn gọi được dịch vụ này.`,
    'Xóa dịch vụ',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  try {
    await serviceCatalogApi.deleteService(service.serviceKey)
    if (selectedKey.value === service.serviceKey) {
      closeOperations()
      selectedKey.value = ''
    }
    await refresh()
    ElMessage.success('Đã xóa dịch vụ')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function openCreate() {
  createForm.serviceKey = ''
  createForm.name = ''
  createVisible.value = true
}

function openEdit(serviceKey: string) {
  const service = services.value.find((item) => item.serviceKey === serviceKey)
  if (!service) return
  editForm.serviceKey = service.serviceKey
  editForm.name = service.name
  editForm.description = service.description || ''
  const baseUrls = service.baseUrls?.length
    ? service.baseUrls.map((row) => ({ environment: row.environment, url: row.url }))
    : [{ environment: 'default', url: '' }]
  editForm.baseUrls.splice(0, editForm.baseUrls.length, ...baseUrls)
  editForm.authType = service.sharedAuth?.type || 'NONE'
  editForm.apiKeyHeader = service.sharedAuth?.apiKeyHeader || ''
  // Header is the historical API key placement when an older service has no location.
  editForm.apiKeyLocation = service.sharedAuth?.apiKeyLocation || 'HEADER'
  editForm.bearerVariable = service.sharedAuth?.bearerVariable || ''
  editForm.tokenUrl = service.sharedAuth?.tokenUrl || ''
  editForm.clientId = service.sharedAuth?.clientId || ''
  editForm.scopes = (service.sharedAuth?.scopes || []).join(',')
  editForm.certificate = ''
  editForm.signatureHeader = service.sharedAuth?.signatureHeader || ''
  editForm.accessKey = ''
  editForm.region = service.sharedAuth?.region || ''
  editForm.awsService = service.sharedAuth?.awsService || ''
  // The API never returns the secret, so the field starts empty and a blank save keeps the stored one.
  editForm.secret = ''
  editVisible.value = true
}

function addBaseUrl() {
  editForm.baseUrls.push({ environment: '', url: '' })
}

async function saveService() {
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('Thiếu tên dịch vụ')
    return
  }
  savingService.value = true
  try {
    await serviceCatalogApi.saveSettings(editForm.serviceKey, {
      name,
      description: editForm.description.trim(),
      baseUrls: editForm.baseUrls
        .map((row) => ({ environment: row.environment.trim(), url: row.url.trim() }))
        .filter((row) => row.environment || row.url),
      sharedAuth: {
        type: editForm.authType,
        apiKeyHeader: editForm.apiKeyHeader.trim(),
        secret: editForm.secret,
        apiKeyLocation: editForm.apiKeyLocation,
        bearerVariable: editForm.bearerVariable.trim(),
        tokenUrl: editForm.tokenUrl.trim(),
        clientId: editForm.clientId.trim(),
        scopes: editForm.scopes.trim(),
        certificate: editForm.certificate,
        signatureHeader: editForm.signatureHeader.trim(),
        accessKey: editForm.accessKey,
        region: editForm.region.trim(),
        awsService: editForm.awsService.trim(),
      },
    })
    editVisible.value = false
    await refresh({ serviceKey: editForm.serviceKey })
    ElMessage.success('Đã lưu dịch vụ')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingService.value = false
  }
}

async function createService() {
  const serviceKey = createForm.serviceKey.trim()
  const name = createForm.name.trim()
  if (!serviceKey) {
    ElMessage.warning('Thiếu mã dịch vụ')
    return
  }
  if (!name) {
    ElMessage.warning('Thiếu tên dịch vụ')
    return
  }
  if (services.value.some((service) => service.serviceKey === serviceKey)) {
    ElMessage.warning('Mã dịch vụ đã tồn tại')
    return
  }
  creating.value = true
  try {
    await serviceCatalogApi.create({ serviceKey, name })
    createVisible.value = false
    await refresh({ serviceKey })
    // A new service has no operations yet, so open its operation list right away.
    openOperations(serviceKey)
    ElMessage.success('Đã lưu dịch vụ')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    creating.value = false
  }
}

async function saveOperation() {
  const message = validateOperationDraft(draft.value)
  if (message) {
    ElMessage.warning(message)
    return
  }
  const serviceKey = selectedKey.value
  const operationKey = draft.value.operationKey.trim()
  saving.value = true
  try {
    await serviceCatalogApi.saveOperation(serviceKey, operationKey, toSaveCommand(draft.value))
    await refresh({ serviceKey, operationKey })
    ElMessage.success('Đã lưu thao tác')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publishService(serviceKey = selectedKey.value) {
  if (!serviceKey) return
  if (serviceKey === selectedKey.value && editorVisible.value && dirty.value) {
    ElMessage.warning('Lưu thao tác trước khi phát hành')
    return
  }
  publishing.value = true
  try {
    await serviceCatalogApi.publish(serviceKey)
    await refresh(
      serviceKey === selectedKey.value
        ? { serviceKey, operationKey: editingKey.value || undefined }
        : undefined,
    )
    ElMessage.success('Đã phát hành. Những lần chạy sau dùng bản này')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishing.value = false
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="management-page page-stack service-catalog">
    <PageHeader
      eyebrow="Tích hợp"
      title="Dịch vụ"
      description="Khai báo API bên ngoài. Trong quy trình, bạn chỉ chọn dịch vụ và thao tác."
    >
      <template #actions>
        <el-button type="primary" data-action="create-service" @click="openCreate">
          <Plus :size="17" />Tạo dịch vụ
        </el-button>
      </template>
    </PageHeader>

    <section class="page-actions compact-filter">
      <el-form class="filter-form" inline @submit.prevent>
        <el-form-item label="Tìm dịch vụ">
          <el-input
            v-model="query"
            clearable
            aria-label="Tìm dịch vụ"
            placeholder="Mã hoặc tên dịch vụ"
          >
            <template #prefix><Search :size="15" /></template>
          </el-input>
        </el-form-item>
        <el-form-item class="filter-form__actions">
          <el-button data-action="reload" :loading="loading" @click="refresh()">
            <RefreshCw :size="16" />Tải lại
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="table-panel elevated-panel">
      <el-table
        v-loading="loading"
        :data="filteredServices"
        row-key="serviceKey"
        table-layout="fixed"
      >
        <el-table-column label="STT" width="64" align="center">
          <template #default="{ $index }">
            <span class="catalog-index">{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Dịch vụ" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="definition-name-cell">
              <span class="definition-avatar"><Server :size="16" /></span>
              <div>
                <strong>{{ row.name }}</strong>
                <small>{{ row.serviceKey }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Mô tả" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="Địa chỉ gốc" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ baseUrlLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="Xác thực" width="130">
          <template #default="{ row }">{{ authLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="Số thao tác" width="110" align="center">
          <template #default="{ row }">{{ row.operations.length }}</template>
        </el-table-column>
        <el-table-column label="Trạng thái" width="160">
          <template #default="{ row }">
            <TableTagCell>
              <el-tag
                :type="serviceStatusTag[serviceStatus(row)]"
                effect="light"
                round
                :data-status="serviceStatus(row)"
              >
                {{ serviceStatusLabel[serviceStatus(row)] }}
              </el-tag>
            </TableTagCell>
          </template>
        </el-table-column>
        <el-table-column label="Hành động" width="340" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                link
                type="primary"
                data-action="open-operations"
                :data-service="row.serviceKey"
                @click="openOperations(row.serviceKey)"
              >
                <ListTree :size="14" />Thao tác
              </el-button>
              <el-button
                link
                type="primary"
                data-action="edit-service"
                :data-service="row.serviceKey"
                @click="openEdit(row.serviceKey)"
              >
                <SquarePen :size="14" />Sửa
              </el-button>
              <el-button
                link
                type="success"
                data-action="publish"
                :data-service="row.serviceKey"
                :disabled="publishing"
                @click="publishService(row.serviceKey)"
              >
                <Rocket :size="14" />Phát hành
              </el-button>
              <el-button
                link
                type="danger"
                data-action="delete-service"
                :data-service="row.serviceKey"
                @click="removeService(row.serviceKey)"
              >
                <Trash2 :size="14" />Xóa
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            v-if="!services.length"
            title="Chưa có dịch vụ"
            description="Tạo một dịch vụ để khai báo API bên ngoài."
          >
            <el-button type="primary" @click="openCreate"><Plus :size="16" />Tạo dịch vụ</el-button>
          </ListEmptyState>
          <p v-else class="catalog-no-match">Không có dịch vụ khớp “{{ query.trim() }}”.</p>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-if="operationsVisible && selectedService"
      class="catalog-operations-dialog"
      :model-value="true"
      :title="`Thao tác · ${selectedService.name}`"
      width="min(1120px, calc(100vw - 32px))"
      :before-close="closeOperations"
    >
      <div class="catalog-operations__summary">
        <div>
          <code>{{ selectedService.serviceKey }}</code>
          <el-tag
            :type="serviceStatusTag[serviceStatus(selectedService)]"
            effect="light"
            round
          >
            {{ serviceStatusLabel[serviceStatus(selectedService)] }}
          </el-tag>
        </div>
        <el-button
          type="primary"
          data-action="publish-operations"
          :loading="publishing"
          @click="publishService(selectedService.serviceKey)"
        >
          <Rocket :size="15" />Phát hành dịch vụ
        </el-button>
      </div>
      <OperationTable
        :operations="selectedService.operations"
        :loading="loading"
        @select="selectOperation"
        @add="addOperation"
        @remove="removeOperation"
      />
      <template #footer>
        <el-button @click="closeOperations">Đóng</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-if="editorVisible"
      class="catalog-operation-dialog"
      :model-value="true"
      :title="editingKey ? 'Sửa thao tác' : 'Thêm thao tác'"
      width="min(760px, calc(100vw - 32px))"
      append-to-body
      :before-close="closeEditor"
    >
      <OperationEditor
        v-model="draft"
        :operation-key-locked="editingKey !== null"
        :secret-present="secretPresent"
        :saving="saving"
        :shared-auth-type="selectedService?.sharedAuth?.type || 'NONE'"
        @save="saveOperation"
      />
      <template #footer>
        <el-button @click="closeEditor()">Hủy</el-button>
      </template>
    </el-dialog>

    <el-dialog v-if="editVisible" v-model="editVisible" title="Sửa dịch vụ" width="640px">
      <el-form label-position="top">
        <el-form-item label="Mã dịch vụ">
          <el-input v-model="editForm.serviceKey" data-field="edit-service-key" disabled />
        </el-form-item>
        <el-form-item label="Tên dịch vụ">
          <el-input v-model="editForm.name" data-field="edit-service-name" placeholder="CRM" />
        </el-form-item>
        <el-form-item label="Mô tả">
          <el-input
            v-model="editForm.description"
            data-field="edit-service-description"
            type="textarea"
            :rows="2"
          />
        </el-form-item>
        <el-form-item label="Địa chỉ gốc theo môi trường">
          <div v-for="(row, index) in editForm.baseUrls" :key="`base-${index}`" class="base-url-row">
            <el-input v-model="row.environment" data-field="edit-base-environment" placeholder="default" />
            <el-input v-model="row.url" data-field="edit-base-url" placeholder="https://crm.example" />
            <el-button @click="editForm.baseUrls.splice(index, 1)">Xóa</el-button>
          </div>
          <el-button @click="addBaseUrl">Thêm môi trường</el-button>
        </el-form-item>
        <el-form-item label="Xác thực dùng chung">
          <el-select v-model="editForm.authType" data-field="edit-shared-auth">
            <el-option
              v-for="auth in authTypes"
              :key="auth.value"
              :label="auth.label"
              :value="auth.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'API_KEY'" label="Vị trí API key">
          <el-select v-model="editForm.apiKeyLocation" data-field="edit-api-key-location">
            <el-option label="Header" value="HEADER" />
            <el-option label="Query" value="QUERY" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="editForm.authType === 'API_KEY'"
          :label="editForm.apiKeyLocation === 'QUERY' ? 'Tên query' : 'Header API Key'"
        >
          <el-input v-model="editForm.apiKeyHeader" data-field="edit-api-key-header" placeholder="X-Api-Key" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'BEARER_VARIABLE'" label="Biến Bearer">
          <el-input v-model="editForm.bearerVariable" data-field="edit-bearer-variable" />
        </el-form-item>
        <template v-if="editForm.authType === 'OAUTH2_CLIENT'">
          <el-form-item label="URL token">
            <el-input v-model="editForm.tokenUrl" data-field="edit-token-url" />
          </el-form-item>
          <el-form-item label="Client id">
            <el-input v-model="editForm.clientId" data-field="edit-client-id" />
          </el-form-item>
          <el-form-item label="Scope">
            <el-input v-model="editForm.scopes" data-field="edit-scopes" />
          </el-form-item>
        </template>
        <el-form-item v-if="editForm.authType === 'MTLS'" label="Chứng chỉ PEM">
          <el-input v-model="editForm.certificate" data-field="edit-certificate" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'HMAC'" label="Header chữ ký">
          <el-input v-model="editForm.signatureHeader" data-field="edit-signature-header" />
        </el-form-item>
        <template v-if="editForm.authType === 'AWS_SIG_V4'">
          <el-form-item label="Access key">
            <el-input v-model="editForm.accessKey" data-field="edit-access-key" />
          </el-form-item>
          <el-form-item label="Region">
            <el-input v-model="editForm.region" data-field="edit-aws-region" />
          </el-form-item>
          <el-form-item label="Service">
            <el-input v-model="editForm.awsService" data-field="edit-aws-service" />
          </el-form-item>
        </template>
        <el-form-item v-if="editForm.authType !== 'BEARER_VARIABLE'" label="Khóa dùng chung">
          <el-input
            v-model="editForm.secret"
            data-field="edit-shared-secret"
            type="password"
            show-password
            placeholder="Để trống để giữ khóa đã lưu"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">Hủy</el-button>
        <el-button type="primary" data-action="save-service" :loading="savingService" @click="saveService">
          Lưu dịch vụ
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-if="createVisible" v-model="createVisible" title="Tạo dịch vụ" width="480px">
      <el-form label-position="top">
        <el-form-item label="Mã dịch vụ">
          <el-input v-model="createForm.serviceKey" data-field="service-key" placeholder="crm" />
        </el-form-item>
        <el-form-item label="Tên dịch vụ">
          <el-input v-model="createForm.name" data-field="service-name" placeholder="CRM" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="creating" @click="createService">Lưu dịch vụ</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.catalog-index {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.catalog-no-match {
  margin: 24px 0;
  color: var(--color-text-muted);
}

.catalog-operations__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.catalog-operations__summary > div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.catalog-operations__summary code {
  color: var(--color-text-muted);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 13px;
}

.base-url-row {
  display: grid;
  grid-template-columns: minmax(100px, 0.6fr) minmax(0, 1.4fr) auto;
  gap: 8px;
  margin-bottom: 8px;
}

</style>
