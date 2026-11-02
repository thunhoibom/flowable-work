<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { confirmAction } from '@/utils/confirmation'
import { serviceCatalogApi, type CatalogOperation, type ServiceView } from './api'
import OperationEditor from './OperationEditor.vue'
import OperationTable from './OperationTable.vue'
import ServiceListPanel from './ServiceListPanel.vue'
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
  secret: '',
})

const selectedService = computed(() =>
  services.value.find((service) => service.serviceKey === selectedKey.value),
)
const dirty = computed(() => isOperationDraftDirty(draft.value, baseline.value))

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
    const service =
      services.value.find((item) => preferred && item.serviceKey === preferred) || services.value[0]
    if (!service) {
      selectedKey.value = ''
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

async function selectService(serviceKey: string) {
  if (serviceKey === selectedKey.value) return
  if (!(await confirmDiscard())) return
  selectedKey.value = serviceKey
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

async function removeService() {
  const service = selectedService.value
  if (!service?.serviceKey) return
  const confirmed = await confirmAction(
    `Xóa dịch vụ “${service.name}”? Những lần chạy sau sẽ không còn gọi được dịch vụ này.`,
    'Xóa dịch vụ',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  try {
    await serviceCatalogApi.deleteService(service.serviceKey)
    editorVisible.value = false
    selectedKey.value = ''
    loadNew()
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

function openEdit() {
  const service = selectedService.value
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
  if (!(await confirmDiscard())) return
  creating.value = true
  try {
    await serviceCatalogApi.create({ serviceKey, name })
    createVisible.value = false
    await refresh({ serviceKey })
    loadNew()
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

async function publishService() {
  if (!selectedKey.value) return
  if (dirty.value) {
    ElMessage.warning('Lưu thao tác trước khi phát hành')
    return
  }
  publishing.value = true
  try {
    await serviceCatalogApi.publish(selectedKey.value)
    await refresh({ serviceKey: selectedKey.value, operationKey: editingKey.value || undefined })
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
  <div class="page-stack service-catalog">
    <PageHeader
      eyebrow="Tích hợp"
      title="Dịch vụ"
      description="Khai báo API bên ngoài. Trong quy trình, bạn chỉ chọn dịch vụ và thao tác."
    >
      <template #actions>
        <el-button @click="openCreate">Tạo dịch vụ</el-button>
      </template>
    </PageHeader>

    <p v-if="loading && !services.length">Đang tải</p>
    <ListEmptyState
      v-else-if="!services.length"
      title="Chưa có dịch vụ"
      description="Tạo một dịch vụ để khai báo API bên ngoài."
    >
      <el-button type="primary" @click="openCreate">Tạo dịch vụ</el-button>
    </ListEmptyState>
    <div v-else class="catalog-workspace">
      <ServiceListPanel
        v-model:query="query"
        :services="services"
        :selected-key="selectedKey"
        @select="selectService"
      />
      <section v-if="selectedService" class="catalog-detail">
        <header>
          <div>
            <h3>{{ selectedService.name }}</h3>
            <p>{{ selectedService.serviceKey }}</p>
          </div>
          <div class="catalog-detail__actions">
            <el-button data-action="edit-service" @click="openEdit">Sửa dịch vụ</el-button>
            <el-button data-action="delete-service" @click="removeService">Xóa dịch vụ</el-button>
            <el-button data-action="publish" type="primary" :loading="publishing" @click="publishService">
              Phát hành
            </el-button>
          </div>
        </header>
        <OperationTable
          :operations="selectedService.operations"
          :selected-operation-key="editorVisible ? editingKey || '' : ''"
          @select="selectOperation"
          @add="addOperation"
          @remove="removeOperation"
        />
      </section>
    </div>

    <el-dialog
      v-if="editorVisible"
      class="catalog-operation-dialog"
      :model-value="true"
      :title="editingKey ? 'Sửa thao tác' : 'Thêm thao tác'"
      width="min(760px, calc(100vw - 32px))"
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
        <el-form-item v-if="editForm.authType === 'API_KEY'" label="Header API Key">
          <el-input v-model="editForm.apiKeyHeader" data-field="edit-api-key-header" placeholder="X-Api-Key" />
        </el-form-item>
        <el-form-item label="Khóa dùng chung">
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
.catalog-workspace {
  display: grid;
  grid-template-columns: minmax(220px, 300px) minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.catalog-detail {
  display: grid;
  align-content: start;
  gap: 20px;
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.catalog-detail header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.catalog-detail header > div:first-child {
  min-width: 0;
}

.catalog-detail__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.base-url-row {
  display: grid;
  grid-template-columns: minmax(100px, 0.6fr) minmax(0, 1.4fr) auto;
  gap: 8px;
  margin-bottom: 8px;
}

.catalog-detail h3,
.catalog-detail p {
  margin: 0;
}

.catalog-detail h3 {
  overflow: hidden;
  font-size: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-detail header p {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-catalog {
  font-size: 15px;
}

@media (max-width: 959px) {
  .catalog-workspace {
    grid-template-columns: 1fr;
  }
}
</style>
