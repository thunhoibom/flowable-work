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
const publishing = ref(false)
const createVisible = ref(false)
const query = ref('')
const selectedKey = ref('')
const editingKey = ref<string | null>(null)
const secretPresent = ref(false)
const draft = ref<OperationDraft>(blankOperationDraft())
const baseline = ref<OperationDraft>(blankOperationDraft())
const createForm = reactive({ serviceKey: '', name: '' })

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
    services.value = await serviceCatalogApi.list()
    const service =
      services.value.find((item) => item.serviceKey === (prefer?.serviceKey || selectedKey.value)) ||
      services.value[0]
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
  loadNew()
}

async function selectOperation(operationKey: string) {
  if (operationKey === editingKey.value) return
  if (!(await confirmDiscard())) return
  const operation = selectedService.value?.operations.find((item) => item.operationKey === operationKey)
  if (operation) loadOperation(operation)
}

async function addOperation() {
  if (editingKey.value === null && !dirty.value) return
  if (!(await confirmDiscard())) return
  loadNew()
}

function openCreate() {
  createForm.serviceKey = ''
  createForm.name = ''
  createVisible.value = true
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
          <el-button data-action="publish" type="primary" :loading="publishing" @click="publishService">
            Phát hành
          </el-button>
        </header>
        <OperationTable
          :operations="selectedService.operations"
          :selected-operation-key="editingKey || ''"
          @select="selectOperation"
          @add="addOperation"
        />
        <OperationEditor
          v-model="draft"
          :operation-key-locked="editingKey !== null"
          :secret-present="secretPresent"
          :saving="saving"
          @save="saveOperation"
        />
      </section>
    </div>

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
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
}

.catalog-detail {
  display: grid;
  align-content: start;
  gap: 20px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.catalog-detail header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.catalog-detail h3,
.catalog-detail p {
  margin: 0;
}

.catalog-detail header p {
  color: var(--color-text-muted);
  font-size: 13px;
}

@media (max-width: 959px) {
  .catalog-workspace {
    grid-template-columns: 1fr;
  }
}
</style>
