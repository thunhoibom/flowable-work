<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Bot, CirclePlay, Plus, RefreshCw, Search, ServerCog, ShieldCheck } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import AgentProviderTable from '../components/AgentProviderTable.vue'
import TableTagCell from '@/components/TableTagCell.vue'
import TablePagination from '@/components/TablePagination.vue'
import { APP_PERMISSION, APP_ROLE, hasAccess } from '@/features/auth/authorization'
import { useAuthStore } from '@/stores/auth'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime, formatVersion } from '@/utils/format'
import { getStatusLabel } from '@/utils/status'
import { agentApi, agentProviderApi, agentRunApi } from '../api'
import { useAgentSchemaEditor } from '../composables/useAgentSchemaEditor'
import SchemaFieldsEditor from '../components/SchemaFieldsEditor.vue'
import { getAgentErrorPresentation } from '../errorPresentation'
import type {
  AgentDefinition,
  AgentDefinitionCommand,
  AgentFailurePolicy,
  AgentProvider,
  AgentProviderCommand,
  AgentProviderType,
  AgentRun,
  AgentRunStatus,
  AgentVersion,
  AgentVersionCommand,
} from '../types'

const authStore = useAuthStore()
const queryClient = useQueryClient()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canManageAgents = computed(() =>
  hasAccess(authStore.user, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.agentManage],
  }),
)
const canReadRuns = computed(() =>
  hasAccess(authStore.user, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.agentRunRead],
  }),
)
const canExecuteRuns = computed(() =>
  hasAccess(authStore.user, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator, APP_ROLE.tenantAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.agentRunExecute],
  }),
)
const activeTab = ref(canManageAgents.value ? 'agents' : 'runs')
const agentRunStatuses: AgentRunStatus[] = [
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'TIMED_OUT',
  'CANCELLED',
]

const agentQuery = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
  pageNum: 1,
  pageSize: 20,
})
const appliedAgentQuery = ref({ ...agentQuery })
const providerQuery = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
  pageNum: 1,
  pageSize: 20,
})
const appliedProviderQuery = ref({ ...providerQuery })
const runQuery = reactive({
  keyword: '',
  status: undefined as AgentRunStatus | undefined,
  pageNum: 1,
  pageSize: 20,
})
const appliedRunQuery = ref({ ...runQuery })

const agentsQuery = useQuery({
  queryKey: computed(() => queryKeys.agentPage(tenantCode.value, appliedAgentQuery.value)),
  queryFn: () => agentApi.page(appliedAgentQuery.value),
  enabled: computed(() => Boolean(tenantCode.value && canManageAgents.value)),
})
const providersQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.agentProviderPage(tenantCode.value, appliedProviderQuery.value),
  ),
  queryFn: () => agentProviderApi.page(appliedProviderQuery.value),
  enabled: computed(() => Boolean(tenantCode.value && canManageAgents.value)),
})
const enabledProvidersQuery = useQuery({
  queryKey: computed(() => queryKeys.enabledAgentProviders(tenantCode.value)),
  queryFn: agentProviderApi.enabled,
  enabled: computed(() => Boolean(tenantCode.value && canManageAgents.value)),
})
const runsQuery = useQuery({
  queryKey: computed(() => queryKeys.agentRunPage(tenantCode.value, appliedRunQuery.value)),
  queryFn: () => agentRunApi.page(appliedRunQuery.value),
  enabled: computed(() => Boolean(tenantCode.value && canReadRuns.value)),
})
const selectedRunId = ref<number>()
const runDetailVisible = ref(false)
const runDetailQuery = useQuery({
  queryKey: computed(() => queryKeys.agentRunDetail(tenantCode.value, selectedRunId.value || 0)),
  queryFn: () => agentRunApi.detail(selectedRunId.value!),
  enabled: computed(() =>
    Boolean(tenantCode.value && selectedRunId.value && runDetailVisible.value),
  ),
  refetchInterval: (query) => {
    const status = query.state.data?.run.status
    return status && !['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(status)
      ? 1000
      : false
  },
})
watch(
  () => runDetailQuery.data.value?.run.status,
  (status) => {
    if (status && ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(status)) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.agentRuns(tenantCode.value) })
    }
  },
)

const manualRunDialogVisible = ref(false)
const manualRunAgent = ref<AgentDefinition>()
const manualRunInput = ref('')
const submitManualRunMutation = useMutation({
  mutationFn: () =>
    agentRunApi.submitManual({
      definitionId: manualRunAgent.value!.id,
      input: manualRunInput.value.trim(),
    }),
  onSuccess: async (submission) => {
    manualRunDialogVisible.value = false
    activeTab.value = 'runs'
    selectedRunId.value = submission.runId
    runDetailVisible.value = true
    ElMessage.success(`Lần chạy thử #${submission.runId} đã vào hàng đợi`)
    await queryClient.invalidateQueries({ queryKey: queryKeys.agentRuns(tenantCode.value) })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

const agentDialogVisible = ref(false)
const editingAgentId = ref<number>()
const agentForm = reactive<AgentDefinitionCommand>({
  code: '',
  name: '',
  description: '',
  enabled: true,
})
const saveAgentMutation = useMutation({
  mutationFn: async () => {
    if (!agentForm.code.trim() || !agentForm.name.trim())
      throw new Error('Mã Agent và tên Agent không được để trống')
    const payload = {
      ...agentForm,
      code: agentForm.code.trim(),
      name: agentForm.name.trim(),
      description: agentForm.description?.trim(),
    }
    return editingAgentId.value
      ? agentApi.update(editingAgentId.value, payload)
      : agentApi.create(payload)
  },
  onSuccess: async () => {
    agentDialogVisible.value = false
    ElMessage.success(
      editingAgentId.value ? 'Đã cập nhật thông tin Agent' : `Đã tạo Agent và sinh bản nháp ${formatVersion(1)}`,
    )
    await queryClient.invalidateQueries({ queryKey: queryKeys.agents(tenantCode.value) })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

const versionDialogVisible = ref(false)
const versionEditorVisible = ref(false)
const selectedAgent = ref<AgentDefinition>()
const editingVersion = ref<AgentVersion>()
const versionForm = reactive<AgentVersionCommand>({
  executionMode: 'MODEL_ONLY',
  providerId: undefined,
  modelName: '',
  systemPrompt: '',
  timeoutSeconds: 120,
  failurePolicy: 'FAIL_PROCESS',
  inputSchema: '',
  outputSchema: '',
})
const {
  inputSchemaFields,
  outputSchemaFields,
  reset: resetSchemaFields,
  buildInputSchema,
  buildOutputSchema,
  addInputSchemaField,
  addOutputSchemaField,
  removeInputSchemaField,
} = useAgentSchemaEditor()
const versionsQuery = useQuery({
  queryKey: computed(() => queryKeys.agentVersions(tenantCode.value, selectedAgent.value?.id || 0)),
  queryFn: () => agentApi.versions(selectedAgent.value!.id),
  enabled: computed(() => Boolean(selectedAgent.value?.id && versionDialogVisible.value)),
})
const versions = computed(() => versionsQuery.data.value ?? [])
const hasDraft = computed(() => versions.value.some((item) => item.status === 'DRAFT'))
const createDraftMutation = useMutation({
  mutationFn: () => agentApi.createDraft(selectedAgent.value!.id),
  onSuccess: async (version) => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.agentVersions(tenantCode.value, selectedAgent.value!.id),
    })
    openVersionEditor(version)
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const saveVersionMutation = useMutation({
  mutationFn: () =>
    agentApi.updateDraft(selectedAgent.value!.id, editingVersion.value!.id, {
      ...versionForm,
      modelName: versionForm.modelName?.trim(),
      systemPrompt: versionForm.systemPrompt.trim(),
      inputSchema: buildInputSchema(),
      outputSchema: buildOutputSchema(),
    }),
  onSuccess: async () => {
    versionEditorVisible.value = false
    ElMessage.success('Đã lưu bản nháp Agent')
    await refreshVersions()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const publishMutation = useMutation({
  mutationFn: (version: AgentVersion) => agentApi.publish(version.definitionId, version.id),
  onSuccess: async () => {
    ElMessage.success('Đã phát hành. Phiên bản này không sửa được nữa')
    await Promise.all([
      refreshVersions(),
      queryClient.invalidateQueries({ queryKey: queryKeys.agents(tenantCode.value) }),
    ])
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

const providerDialogVisible = ref(false)
const editingProviderId = ref<number>()
const providerForm = reactive<AgentProviderCommand>({
  code: '',
  name: '',
  type: 'MOCK',
  baseUrl: '',
  defaultModel: '',
  credential: '',
  enabled: true,
})
const saveProviderMutation = useMutation({
  mutationFn: async () => {
    if (!providerForm.code.trim() || !providerForm.name.trim())
      throw new Error('Mã nhà cung cấp và tên nhà cung cấp không được để trống')
    const payload = {
      ...providerForm,
      code: providerForm.code.trim(),
      name: providerForm.name.trim(),
      baseUrl: providerForm.baseUrl?.trim(),
      defaultModel: providerForm.defaultModel?.trim(),
      credential: providerForm.credential?.trim(),
    }
    return editingProviderId.value
      ? agentProviderApi.update(editingProviderId.value, payload)
      : agentProviderApi.create(payload)
  },
  onSuccess: async () => {
    providerDialogVisible.value = false
    ElMessage.success(editingProviderId.value ? 'Đã cập nhật nhà cung cấp' : 'Đã tạo nhà cung cấp')
    await queryClient.invalidateQueries({ queryKey: queryKeys.agentProviders(tenantCode.value) })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function searchAgents() {
  agentQuery.pageNum = 1
  appliedAgentQuery.value = { ...agentQuery }
}
function resetAgents() {
  Object.assign(agentQuery, { keyword: '', enabled: undefined, pageNum: 1 })
  appliedAgentQuery.value = { ...agentQuery }
}
function changeAgentPage(pageNum: number, pageSize: number) {
  Object.assign(agentQuery, { pageNum, pageSize })
  appliedAgentQuery.value = { ...agentQuery }
}
function openAgentCreate() {
  editingAgentId.value = undefined
  Object.assign(agentForm, { code: '', name: '', description: '', enabled: true })
  agentDialogVisible.value = true
}
function openAgentEdit(agent: AgentDefinition) {
  editingAgentId.value = agent.id
  Object.assign(agentForm, {
    code: agent.code,
    name: agent.name,
    description: agent.description || '',
    enabled: agent.enabled,
  })
  agentDialogVisible.value = true
}
function openManualRun(agent: AgentDefinition) {
  manualRunAgent.value = agent
  manualRunInput.value = ''
  manualRunDialogVisible.value = true
}
function openVersions(agent: AgentDefinition) {
  selectedAgent.value = agent
  versionDialogVisible.value = true
}
function openVersionEditor(version: AgentVersion) {
  editingVersion.value = version
  Object.assign(versionForm, {
    executionMode: version.executionMode || 'MODEL_ONLY',
    providerId: version.providerId,
    modelName: version.modelName || '',
    systemPrompt: version.systemPrompt || '',
    timeoutSeconds: version.timeoutSeconds,
    failurePolicy: version.failurePolicy,
    inputSchema: version.inputSchema || '',
    outputSchema: version.outputSchema || '',
  })
  resetSchemaFields(version.inputSchema, version.outputSchema)
  versionEditorVisible.value = true
}

async function publishVersion(version: AgentVersion) {
  const confirmed = await confirmAction(
    `Sau khi phát hành phiên bản ${version.version}, không sửa được nữa. Muốn đổi thì tạo phiên bản mới. Tiếp tục?`,
    'Phát hành phiên bản Agent',
    { confirmButtonText: 'Phát hành', cancelButtonText: 'Hủy', type: 'warning' },
  )
  if (confirmed) publishMutation.mutate(version)
}
async function refreshVersions() {
  if (!selectedAgent.value) return
  await queryClient.invalidateQueries({
    queryKey: queryKeys.agentVersions(tenantCode.value, selectedAgent.value.id),
  })
}

function searchProviders() {
  providerQuery.pageNum = 1
  appliedProviderQuery.value = { ...providerQuery }
}
function resetProviders() {
  Object.assign(providerQuery, { keyword: '', enabled: undefined, pageNum: 1 })
  appliedProviderQuery.value = { ...providerQuery }
}
function changeProviderPage(pageNum: number, pageSize: number) {
  Object.assign(providerQuery, { pageNum, pageSize })
  appliedProviderQuery.value = { ...providerQuery }
}
function openProviderCreate() {
  editingProviderId.value = undefined
  Object.assign(providerForm, {
    code: '',
    name: '',
    type: 'MOCK',
    baseUrl: '',
    defaultModel: '',
    credential: '',
    enabled: true,
  })
  providerDialogVisible.value = true
}
function openProviderEdit(provider: AgentProvider) {
  editingProviderId.value = provider.id
  Object.assign(providerForm, {
    code: provider.code,
    name: provider.name,
    type: provider.type,
    baseUrl: provider.baseUrl || '',
    defaultModel: provider.defaultModel || '',
    credential: '',
    enabled: provider.enabled,
  })
  providerDialogVisible.value = true
}

function searchRuns() {
  runQuery.pageNum = 1
  appliedRunQuery.value = { ...runQuery }
}
function resetRuns() {
  Object.assign(runQuery, { keyword: '', status: undefined, pageNum: 1 })
  appliedRunQuery.value = { ...runQuery }
}
function changeRunPage(pageNum: number, pageSize: number) {
  Object.assign(runQuery, { pageNum, pageSize })
  appliedRunQuery.value = { ...runQuery }
}
function openRunDetail(run: AgentRun) {
  selectedRunId.value = run.id
  runDetailVisible.value = true
}

function providerTypeLabel(type: AgentProviderType) {
  return type === 'MOCK' ? 'Mock (thử nghiệm)' : 'Tương thích OpenAI'
}
function failurePolicyLabel(policy: AgentFailurePolicy) {
  return { FAIL_PROCESS: 'Kết thúc quy trình', CONTINUE_EMPTY: 'Tiếp tục với kết quả rỗng', MANUAL_REVIEW: 'Chuyển cho người xử lý' }[
    policy
  ]
}
function snapshotField(snapshot: string | undefined, field: string) {
  if (!snapshot) return ''
  try {
    const value = JSON.parse(snapshot) as Record<string, unknown>
    return typeof value[field] === 'string' ? value[field] : JSON.stringify(value[field] ?? '')
  } catch {
    return snapshot
  }
}

function recoveryActionLabel(action: string) {
  return (
    {
      RETRY_PROVIDER: 'Thử lại',
      REPAIR_OUTPUT: 'Sửa kết quả',
      REPAIR_TOOL_CALL: 'Sửa cách gọi công cụ',
      WAIT_FOR_REVIEW: 'Chờ xem xét',
      FIX_CONFIGURATION: 'Sửa cấu hình',
      REJECT_BUSINESS: 'Từ chối',
      TERMINATE: 'Kết thúc',
    }[action] || action
  )
}

function failureCategoryLabel(category: string) {
  return (
    {
      PROVIDER_TRANSIENT: 'Nhà cung cấp tạm lỗi',
      OUTPUT_CONTRACT: 'Kết quả không đúng mẫu',
      TOOL_PROTOCOL: 'Gọi công cụ bị lỗi',
      INPUT_CONTRACT: 'Dữ liệu vào không đúng mẫu',
      CONFIGURATION: 'Cấu hình chưa đúng',
      BUSINESS_REJECTION: 'Bị từ chối',
      EXECUTION_UNEXPECTED: 'Lỗi chưa rõ',
      DEADLINE: 'Quá hạn',
    }[category] || category
  )
}
</script>

<template>
  <div class="management-page page-stack agent-management-page">
    <PageHeader
      eyebrow="Trợ lý"
      title="Agent"
      description="Tạo Agent, phát hành phiên bản và xem từng lần chạy."
    />

    <section class="agent-overview">
      <div>
        <span class="overview-icon"><Bot :size="19" /></span>
        <p><strong>Agent</strong><small>Soạn nội dung, chọn mô hình và cách xử lý khi lỗi</small></p>
      </div>
      <div>
        <span class="overview-icon tone-purple"><ServerCog :size="19" /></span>
        <p><strong>Nhà cung cấp</strong><small>Địa chỉ, mô hình và khóa được lưu an toàn</small></p>
      </div>
      <div>
        <span class="overview-icon tone-green"><CirclePlay :size="19" /></span>
        <p><strong>Nhật ký</strong><small>Xem trạng thái, quy trình liên quan và lỗi</small></p>
      </div>
      <div>
        <span class="overview-icon tone-amber"><ShieldCheck :size="19" /></span>
        <p><strong>An toàn</strong><small>Khóa không hiện lại. Dữ liệu tách theo tổ chức</small></p>
      </div>
    </section>

    <section class="agent-workspace">
      <el-tabs v-model="activeTab" class="agent-tabs">
        <el-tab-pane v-if="canManageAgents" label="Agent" name="agents">
          <div class="agent-tab-content">
            <section class="page-actions compact-filter">
              <el-form class="filter-form filter-form--agent" inline @submit.prevent="searchAgents">
                <el-form-item label="Từ khóa"
                  ><el-input v-model="agentQuery.keyword" clearable placeholder="Mã hoặc tên Agent"
                /></el-form-item>
                <el-form-item label="Trạng thái"
                  ><el-select v-model="agentQuery.enabled" clearable
                    ><el-option label="Bật" :value="true" /><el-option
                      label="Tắt"
                      :value="false" /></el-select
                ></el-form-item>
                <el-form-item class="filter-form__actions"
                  ><el-button type="primary" native-type="submit"
                    ><Search :size="16" />Tìm</el-button
                  ><el-button @click="resetAgents"
                    ><RefreshCw :size="16" />Đặt lại</el-button
                  ></el-form-item
                >
              </el-form>
              <el-button class="page-primary-action" type="primary" @click="openAgentCreate"
                ><Plus :size="17" />Tạo Agent</el-button
              >
            </section>
            <section class="agent-table-panel table-panel">
              <el-table
                v-loading="agentsQuery.isFetching.value"
                :data="agentsQuery.data.value?.records ?? []"
                height="100%"
              >
                <el-table-column label="Agent" min-width="230"
                  ><template #default="{ row }"
                    ><div class="agent-name-cell">
                      <span>{{ row.name.slice(0, 1).toUpperCase() }}</span>
                      <p>
                        <strong>{{ row.name }}</strong
                        ><small>{{ row.code }}</small>
                      </p>
                    </div></template
                  ></el-table-column
                >
                <el-table-column label="Đang dùng" width="125" header-align="center"
                  ><template #default="{ row }"
                    ><TableTagCell>
                      <StatusBadge
                        class="version-status-badge"
                        v-if="row.publishedVersion"
                        status="PUBLISHED"
                        :label="formatVersion(row.publishedVersion)"
                      />
                      <span v-else>—</span>
                    </TableTagCell>
                  </template></el-table-column
                >
                <el-table-column label="Phiên bản nháp" width="125" header-align="center"
                  ><template #default="{ row }"
                    ><TableTagCell>
                      <StatusBadge
                        class="version-status-badge"
                        v-if="row.latestVersion && row.latestVersion !== row.publishedVersion"
                        status="DRAFT"
                        :label="formatVersion(row.latestVersion)"
                      />
                      <span v-else>—</span>
                    </TableTagCell>
                  </template></el-table-column
                >
                <el-table-column
                  prop="description"
                  label="Mô tả"
                  min-width="240"
                  show-overflow-tooltip
                />
                <el-table-column
                  class-name="agent-status-column"
                  label="Trạng thái"
                  width="110"
                  align="center"
                  header-align="center"
                  ><template #default="{ row }"><StatusBadge :status="row.enabled" /></template
                ></el-table-column>
                <el-table-column label="Thời điểm cập nhật" width="175"
                  ><template #default="{ row }">{{
                    formatDateTime(row.updatedAt)
                  }}</template></el-table-column
                >
                <el-table-column label="Thao tác" width="260" fixed="right"
                  ><template #default="{ row }"
                    ><el-button
                      v-if="canExecuteRuns"
                      link
                      type="primary"
                      :disabled="!row.publishedVersion || !row.enabled"
                      @click="openManualRun(row)"
                      >Chạy thử</el-button
                    ><el-button link type="primary" @click="openVersions(row)">Quản lý phiên bản</el-button
                    ><el-button link type="primary" @click="openAgentEdit(row)"
                      >Sửa</el-button
                    ></template
                  ></el-table-column
                >
                <template #empty
                  ><ListEmptyState
                    title="Chưa tạo Agent"
                    description="Tạo Agent trước, rồi cấu hình và phát hành phiên bản đầu tiên."
                    :icon="Bot"
                /></template>
              </el-table>
              <TablePagination
                v-model:current-page="agentQuery.pageNum"
                v-model:page-size="agentQuery.pageSize"
                :total="agentsQuery.data.value?.total ?? 0"
                :page-sizes="[10, 20, 50]"
                aria-label="Phân trang định nghĩa Agent"
                @change="changeAgentPage"
              />
            </section>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canManageAgents" label="Cấu hình nhà cung cấp" name="providers">
          <div class="agent-tab-content">
            <section class="page-actions compact-filter">
              <el-form
                class="filter-form filter-form--agent"
                inline
                @submit.prevent="searchProviders"
              >
                <el-form-item label="Từ khóa"
                  ><el-input
                    v-model="providerQuery.keyword"
                    clearable
                    placeholder="Mã hoặc tên nhà cung cấp"
                /></el-form-item>
                <el-form-item label="Trạng thái"
                  ><el-select v-model="providerQuery.enabled" clearable
                    ><el-option label="Bật" :value="true" /><el-option
                      label="Tắt"
                      :value="false" /></el-select
                ></el-form-item>
                <el-form-item class="filter-form__actions"
                  ><el-button type="primary" native-type="submit"
                    ><Search :size="16" />Tìm</el-button
                  ><el-button @click="resetProviders"
                    ><RefreshCw :size="16" />Đặt lại</el-button
                  ></el-form-item
                >
              </el-form>
              <el-button class="page-primary-action" type="primary" @click="openProviderCreate"
                ><Plus :size="17" />Tạo nhà cung cấp</el-button
              >
            </section>
            <section class="agent-table-panel table-panel">
              <AgentProviderTable
                :rows="providersQuery.data.value?.records ?? []"
                :loading="providersQuery.isFetching.value"
                :provider-type-label="providerTypeLabel"
                @edit="openProviderEdit"
              />
              <TablePagination
                v-model:current-page="providerQuery.pageNum"
                v-model:page-size="providerQuery.pageSize"
                :total="providersQuery.data.value?.total ?? 0"
                :page-sizes="[10, 20, 50]"
                aria-label="Phân trang nhà cung cấp"
                @change="changeProviderPage"
              />
            </section>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canReadRuns" label="Nhật ký chạy" name="runs">
          <div class="agent-tab-content">
            <section class="page-actions compact-filter">
              <el-form class="filter-form filter-form--agent" inline @submit.prevent="searchRuns">
                <el-form-item label="Từ khóa"
                  ><el-input v-model="runQuery.keyword" clearable placeholder="Agent hoặc công việc"
                /></el-form-item>
                <el-form-item label="Trạng thái"
                  ><el-select v-model="runQuery.status" clearable
                    ><el-option
                      v-for="status in agentRunStatuses"
                      :key="status"
                      :label="getStatusLabel(status)"
                      :value="status" /></el-select
                ></el-form-item>
                <el-form-item class="filter-form__actions"
                  ><el-button type="primary" native-type="submit"
                    ><Search :size="16" />Tìm</el-button
                  ><el-button @click="resetRuns"
                    ><RefreshCw :size="16" />Đặt lại</el-button
                  ></el-form-item
                >
              </el-form>
            </section>
            <section class="agent-table-panel table-panel">
              <el-table
                class="agent-run-table"
                v-loading="runsQuery.isFetching.value"
                :data="runsQuery.data.value?.records ?? []"
                height="100%"
              >
                <el-table-column label="Số lần chạy" width="110"
                  ><template #default="{ row }">#{{ row.id }}</template></el-table-column
                >
                <el-table-column label="Agent" min-width="190"
                  ><template #default="{ row }"
                    ><strong>{{ row.agentName }}</strong>
                    <div class="cell-secondary">
                      {{ row.agentCode }} · Phiên bản {{ row.agentVersion }}
                    </div></template
                  ></el-table-column
                >
                <el-table-column label="Trạng thái" width="115" align="center" header-align="center"
                  ><template #default="{ row }"><StatusBadge :status="row.status" /></template
                ></el-table-column>
                <el-table-column prop="processInstanceId" label="Công việc" min-width="180"
                  ><template #default="{ row }">{{
                    row.processInstanceId || '—'
                  }}</template></el-table-column
                >
                <el-table-column prop="activityId" label="Nút quy trình" min-width="150"
                  ><template #default="{ row }">{{
                    row.activityId || '—'
                  }}</template></el-table-column
                >
                <el-table-column prop="errorCode" label="Phân loại lỗi" min-width="150"
                  ><template #default="{ row }">{{
                    row.errorCode || '—'
                  }}</template></el-table-column
                >
                <el-table-column label="Thời điểm tạo" width="175"
                  ><template #default="{ row }">{{
                    formatDateTime(row.createdAt)
                  }}</template></el-table-column
                >
                <el-table-column label="Thao tác" width="90" fixed="right"
                  ><template #default="{ row }"
                    ><el-button link type="primary" @click="openRunDetail(row)"
                      >Chi tiết</el-button
                    ></template
                  ></el-table-column
                >
                <template #empty
                  ><ListEmptyState
                    title="Chưa có nhật ký chạy"
                    description="Sau lần chạy thử, hoặc khi một bước trong quy trình chạy, nhật ký nằm ở đây."
                    :icon="CirclePlay"
                /></template>
              </el-table>
              <TablePagination
                v-model:current-page="runQuery.pageNum"
                v-model:page-size="runQuery.pageSize"
                :total="runsQuery.data.value?.total ?? 0"
                :page-sizes="[10, 20, 50]"
                aria-label="Phân trang nhật ký chạy"
                @change="changeRunPage"
              />
            </section>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog
      v-model="manualRunDialogVisible"
      :title="`Chạy thử · ${manualRunAgent?.name || ''}`"
      width="620px"
      destroy-on-close
    >
      <div class="manual-run-intro">
        <CirclePlay :size="20" />
        <p>
          <strong>Chạy bản đã phát hành</strong>
          <span>Yêu cầu này tạo một lần chạy Agent chính thức, và ghi lần thử, lần gọi mô hình cùng lịch sử trạng thái.</span>
        </p>
      </div>
      <el-form label-position="top">
        <el-form-item label="Nội dung thử" required>
          <el-input
            v-model="manualRunInput"
            type="textarea"
            :rows="7"
            maxlength="20000"
            show-word-limit
            placeholder="Nhập một nội dung nghiệp vụ thật để kiểm tra prompt và phản hồi của mô hình"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualRunDialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :disabled="!manualRunInput.trim()"
          :loading="submitManualRunMutation.isPending.value"
          @click="submitManualRunMutation.mutate()"
        >
          <CirclePlay :size="16" />Bắt đầu thử
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="runDetailVisible"
      :title="`Chi tiết lần chạy #${selectedRunId || ''}`"
      size="min(880px, 100vw)"
    >
      <div v-loading="runDetailQuery.isFetching.value" class="run-detail">
        <template v-if="runDetailQuery.data.value">
          <section class="run-summary-card">
            <div>
              <small>Agent</small>
              <strong>{{ runDetailQuery.data.value.run.agentName }}</strong>
              <span class="run-summary-card__meta"
                >{{ runDetailQuery.data.value.run.agentCode }} · Phiên bản
                {{ runDetailQuery.data.value.run.agentVersion }}</span
              >
            </div>
            <div class="run-status-summary">
              <small>Trạng thái chạy</small>
              <StatusBadge :status="runDetailQuery.data.value.run.status" />
            </div>
            <div>
              <small>Quy trình liên kết</small>
              <strong>{{ runDetailQuery.data.value.run.processInstanceId || 'Chưa liên kết' }}</strong>
              <span class="run-summary-card__meta">{{
                runDetailQuery.data.value.run.activityId || '—'
              }}</span>
            </div>
          </section>

          <section class="run-diagnostics-card">
            <div>
              <small>Trace ID</small>
              <code>{{ runDetailQuery.data.value.run.traceId || '—' }}</code>
            </div>
            <div>
              <small>Lần thử hiện tại</small>
              <strong>{{ runDetailQuery.data.value.attempts.at(-1)?.attemptNo || '—' }}</strong>
            </div>
            <div>
              <small>Bước hiện tại</small>
              <strong>{{ runDetailQuery.data.value.steps.at(-1)?.stepType || '—' }}</strong>
            </div>
            <div>
              <small>Trạng thái khôi phục</small>
              <strong>{{
                runDetailQuery.data.value.recoveryDecisions.at(-1)?.requiresHumanReview
                  ? 'Chờ xem xét'
                  : runDetailQuery.data.value.recoveryDecisions.at(-1)?.retryScheduled
                    ? 'Sẽ chạy lại'
                    : runDetailQuery.data.value.recoveryDecisions.length
                      ? 'Đã quyết định'
                      : 'Chưa có quyết định'
              }}</strong>
            </div>
          </section>

          <section
            v-if="
              runDetailQuery.data.value.payload.outputSnapshotJson ||
              runDetailQuery.data.value.run.errorCode
            "
            class="run-result-card"
          >
            <small>Kết quả chạy</small>
            <pre v-if="runDetailQuery.data.value.payload.outputSnapshotJson">{{
              snapshotField(runDetailQuery.data.value.payload.outputSnapshotJson, 'content')
            }}</pre>
            <el-alert
              v-else
              :type="
                getAgentErrorPresentation(runDetailQuery.data.value.run.errorCode)?.action ===
                'CONFIGURATION'
                  ? 'warning'
                  : 'error'
              "
              :closable="false"
            >
              <template #title>
                <span class="run-error-title">
                  {{
                    getAgentErrorPresentation(runDetailQuery.data.value.run.errorCode)?.title ||
                    'Agent gặp lỗi chưa rõ'
                  }}
                </span>
              </template>
              <div class="run-error-detail">
                <span>
                  {{
                    getAgentErrorPresentation(runDetailQuery.data.value.run.errorCode)
                      ?.description || 'Hệ thống đã ghi nhận lần thất bại này. Hãy liên hệ quản trị viên.'
                  }}
                </span>
                <span class="run-error-action">
                  {{
                    getAgentErrorPresentation(runDetailQuery.data.value.run.errorCode)?.actionLabel
                  }}
                  · {{ runDetailQuery.data.value.run.errorCode }}
                </span>
              </div>
            </el-alert>
          </section>

          <el-tabs class="run-detail-tabs">
            <el-tab-pane label="Đầu vào và đầu ra">
              <div class="run-payload-grid">
                <article>
                  <strong>Nội dung thử</strong>
                  <pre>{{
                    snapshotField(runDetailQuery.data.value.payload.inputSnapshotJson, 'input') ||
                    '—'
                  }}</pre>
                </article>
                <article>
                  <strong>Đầu ra mô hình</strong>
                  <pre>{{
                    snapshotField(
                      runDetailQuery.data.value.payload.outputSnapshotJson,
                      'content',
                    ) || 'Chờ kết quả chạy'
                  }}</pre>
                </article>
              </div>
            </el-tab-pane>
            <el-tab-pane :label="`Lần thử (${runDetailQuery.data.value.attempts.length})`">
              <el-table :data="runDetailQuery.data.value.attempts" size="small">
                <el-table-column prop="attemptNo" label="Số lần" width="70" />
                <el-table-column label="Trạng thái" width="110">
                  <template #default="{ row }"><StatusBadge :status="row.status" /></template>
                </el-table-column>
                <el-table-column prop="errorCode" label="Phân loại lỗi" min-width="140">
                  <template #default="{ row }">{{ row.errorCode || '—' }}</template>
                </el-table-column>
                <el-table-column label="Thời điểm bắt đầu" width="175">
                  <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`Lần gọi mô hình (${runDetailQuery.data.value.modelInvocations.length})`">
              <el-table :data="runDetailQuery.data.value.modelInvocations" size="small">
                <el-table-column prop="providerName" label="Nhà cung cấp" min-width="130" />
                <el-table-column prop="actualModel" label="Mô hình thực tế" min-width="150">
                  <template #default="{ row }">{{
                    row.actualModel || row.requestedModel
                  }}</template>
                </el-table-column>
                <el-table-column label="Trạng thái" width="105">
                  <template #default="{ row }"><StatusBadge :status="row.status" /></template>
                </el-table-column>
                <el-table-column label="Token" width="115">
                  <template #default="{ row }"
                    >{{ row.inputTokens }} / {{ row.outputTokens }}</template
                  >
                </el-table-column>
                <el-table-column label="Thời lượng" width="100">
                  <template #default="{ row }">{{ row.latencyMillis ?? '—' }} ms</template>
                </el-table-column>
                <el-table-column prop="errorCode" label="Phân loại lỗi" min-width="160">
                  <template #default="{ row }">{{ row.errorCode || '—' }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`Các bước (${runDetailQuery.data.value.steps.length})`">
              <el-table :data="runDetailQuery.data.value.steps" size="small">
                <el-table-column prop="sequenceNo" label="Số thứ tự" width="70" />
                <el-table-column prop="stepType" label="Loại bước" min-width="150" />
                <el-table-column label="Trạng thái" width="110">
                  <template #default="{ row }"><StatusBadge :status="row.status" /></template>
                </el-table-column>
                <el-table-column prop="errorCode" label="Phân loại lỗi" min-width="140">
                  <template #default="{ row }">{{ row.errorCode || '—' }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`Điểm kiểm tra (${runDetailQuery.data.value.checkpoints.length})`">
              <div v-if="runDetailQuery.data.value.checkpoints.length" class="checkpoint-list">
                <article
                  v-for="checkpoint in runDetailQuery.data.value.checkpoints"
                  :key="checkpoint.id"
                >
                  <header>
                    <strong>#{{ checkpoint.sequenceNo }} {{ checkpoint.checkpointType }}</strong>
                    <span>{{ formatDateTime(checkpoint.createdAt) }}</span>
                  </header>
                  <pre>{{ checkpoint.snapshotJson }}</pre>
                </article>
              </div>
              <ListEmptyState
                v-else
                compact
                title="Chưa có điểm chạy lại"
                description="Lần chạy này chưa có điểm để chạy lại."
              />
            </el-tab-pane>
            <el-tab-pane :label="`Lịch sử trạng thái (${runDetailQuery.data.value.stateHistory.length})`">
              <div v-if="runDetailQuery.data.value.stateHistory.length" class="state-history-panel">
                <div class="state-history-guide">
                  <strong>Theo thời gian</strong>
                  <span>Mốc sớm ở trên.</span>
                </div>
                <el-timeline class="state-history-timeline">
                  <el-timeline-item
                    v-for="history in runDetailQuery.data.value.stateHistory"
                    :key="history.id"
                    :timestamp="formatDateTime(history.createdAt)"
                  >
                    <div class="state-transition">
                      <StatusBadge :status="history.oldStatus" />
                      <span>→</span>
                      <StatusBadge :status="history.newStatus" />
                    </div>
                    <p>
                      {{ history.reasonCode }} · {{ history.operatorType }} /
                      {{ history.operatorId }}
                    </p>
                    <small>Trace ID: {{ history.traceId }}</small>
                  </el-timeline-item>
                </el-timeline>
              </div>
              <ListEmptyState
                v-else
                compact
                title="Chưa có đổi trạng thái"
                description="Khi trạng thái đổi, bản ghi hiện ở đây."
              />
            </el-tab-pane>
            <el-tab-pane
              :label="`Quyết định khôi phục (${runDetailQuery.data.value.recoveryDecisions.length})`"
            >
              <el-table :data="runDetailQuery.data.value.recoveryDecisions" size="small">
                <el-table-column label="Phân loại thất bại" min-width="150">
                  <template #default="{ row }">{{
                    failureCategoryLabel(row.failureCategory)
                  }}</template>
                </el-table-column>
                <el-table-column label="Hành động khôi phục" min-width="140">
                  <template #default="{ row }">{{ recoveryActionLabel(row.action) }}</template>
                </el-table-column>
                <el-table-column label="Trạng thái" width="130">
                  <template #default="{ row }">
                    <StatusBadge
                      :status="
                        row.requiresHumanReview
                          ? 'WAITING_FOR_REVIEW'
                          : row.retryScheduled
                            ? 'RETRYING'
                            : 'DECIDED'
                      "
                      :label="
                        row.requiresHumanReview
                          ? 'Chờ xem xét'
                          : row.retryScheduled
                            ? 'Sẽ chạy lại'
                            : 'Đã quyết định'
                      "
                      :tone="
                        row.requiresHumanReview
                          ? 'warning'
                          : row.retryScheduled
                            ? 'primary'
                            : 'success'
                      "
                    />
                  </template>
                </el-table-column>
                <el-table-column prop="reason" label="Ghi chú xử lý" min-width="300" />
                <el-table-column label="Thời điểm" width="175">
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>

    <el-dialog
      v-model="agentDialogVisible"
      :title="editingAgentId ? 'Sửa Agent' : 'Tạo Agent'"
      width="560px"
    >
      <el-form class="dialog-form" label-position="top">
        <el-form-item label="Mã Agent" required
          ><el-input
            v-model="agentForm.code"
            :disabled="Boolean(editingAgentId)"
            maxlength="64"
            placeholder="ví dụ expense_review"
        /></el-form-item>
        <el-form-item label="Tên Agent" required
          ><el-input v-model="agentForm.name" maxlength="128" placeholder="Tên cho người dùng nghiệp vụ"
        /></el-form-item>
        <el-form-item label="Mô tả"
          ><el-input
            v-model="agentForm.description"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
        /></el-form-item>
        <el-form-item label="Bật"><el-switch v-model="agentForm.enabled" /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="agentDialogVisible = false">Hủy</el-button
        ><el-button
          type="primary"
          :loading="saveAgentMutation.isPending.value"
          @click="saveAgentMutation.mutate()"
          >Lưu</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="providerDialogVisible"
      :title="editingProviderId ? 'Sửa nhà cung cấp' : 'Tạo nhà cung cấp'"
      width="620px"
    >
      <el-form class="dialog-form" label-position="top">
        <div class="agent-form-grid">
          <el-form-item label="Mã nhà cung cấp" required
            ><el-input
              v-model="providerForm.code"
              :disabled="Boolean(editingProviderId)"
              maxlength="64"
              placeholder="ví dụ mock_local" /></el-form-item
          ><el-form-item label="Tên nhà cung cấp" required
            ><el-input v-model="providerForm.name" maxlength="128"
          /></el-form-item>
        </div>
        <el-form-item label="Loại nhà cung cấp" required
          ><el-radio-group v-model="providerForm.type"
            ><el-radio-button value="MOCK">Mock (thử nghiệm)</el-radio-button
            ><el-radio-button value="OPENAI_COMPATIBLE"
              >Tương thích OpenAI</el-radio-button
            ></el-radio-group
          ></el-form-item
        >
        <template v-if="providerForm.type === 'OPENAI_COMPATIBLE'">
          <el-form-item label="API Base URL" required
            ><el-input v-model="providerForm.baseUrl" placeholder="https://api.example.com/v1"
          /></el-form-item>
          <el-form-item label="Mô hình mặc định"
            ><el-input v-model="providerForm.defaultModel" placeholder="ví dụ gpt-5-mini"
          /></el-form-item>
          <el-form-item :label="editingProviderId ? 'Đổi API Key' : 'API Key'"
            ><el-input
              v-model="providerForm.credential"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="editingProviderId ? 'Để trống nếu giữ khóa hiện tại' : 'Khóa được lưu an toàn'"
            />
            <div class="form-help">
              Sau khi lưu, giao diện không đọc được khóa gốc, chỉ hiện đã cấu hình hay chưa và bốn ký tự cuối.
            </div></el-form-item
          >
        </template>
        <el-form-item label="Bật"><el-switch v-model="providerForm.enabled" /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="providerDialogVisible = false">Hủy</el-button
        ><el-button
          type="primary"
          :loading="saveProviderMutation.isPending.value"
          @click="saveProviderMutation.mutate()"
          >Lưu</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="versionDialogVisible"
      :title="`${selectedAgent?.name || ''} · Quản lý phiên bản`"
      width="880px"
    >
      <div class="version-toolbar">
        <p>Bản đã phát hành chỉ để xem. Muốn đổi nội dung, mô hình hoặc cách xử lý lỗi thì tạo bản nháp mới.</p>
        <el-button
          type="primary"
          :disabled="hasDraft"
          :loading="createDraftMutation.isPending.value"
          @click="createDraftMutation.mutate()"
          ><Plus :size="16" />Tạo phiên bản mới</el-button
        >
      </div>
      <el-table v-loading="versionsQuery.isFetching.value" :data="versions">
        <el-table-column label="Phiên bản" width="100"
          ><template #default="{ row }"
            ><strong>{{ formatVersion(row.version) }}</strong></template
          ></el-table-column
        >
        <el-table-column label="Trạng thái" width="105"
          ><template #default="{ row }"><StatusBadge :status="row.status" /></template
        ></el-table-column>
        <el-table-column prop="providerName" label="Nhà cung cấp" min-width="150"
          ><template #default="{ row }">{{
            row.providerName || 'Chưa cấu hình'
          }}</template></el-table-column
        >
        <el-table-column prop="modelName" label="Mô hình" min-width="140"
          ><template #default="{ row }">{{
            row.modelName || 'Dùng mô hình mặc định'
          }}</template></el-table-column
        >
        <el-table-column label="Chiến lược khi thất bại" width="120"
          ><template #default="{ row }">{{
            failurePolicyLabel(row.failurePolicy)
          }}</template></el-table-column
        >
        <el-table-column label="Phát hành lúc" width="175"
          ><template #default="{ row }">{{
            formatDateTime(row.publishedAt)
          }}</template></el-table-column
        >
        <el-table-column label="Thao tác" width="150" fixed="right"
          ><template #default="{ row }"
            ><el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              @click="openVersionEditor(row)"
              >Cấu hình</el-button
            ><el-button
              v-if="row.status === 'DRAFT'"
              link
              type="success"
              @click="publishVersion(row)"
              >Phát hành</el-button
            ><el-button v-else link type="primary" @click="openVersionEditor(row)"
              >Xem</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </el-dialog>

    <el-dialog
      v-model="versionEditorVisible"
      :title="`Phiên bản Agent ${editingVersion?.version || ''}`"
      width="760px"
    >
      <el-form class="dialog-form" label-position="top">
        <el-alert
          v-if="editingVersion?.status === 'PUBLISHED'"
          title="Bản này đã phát hành, chỉ xem được."
          type="info"
          :closable="false"
          show-icon
        />
        <el-form-item label="Cách chạy" required>
          <el-select
            v-model="versionForm.executionMode"
            :disabled="editingVersion?.status === 'PUBLISHED'"
          >
            <el-option label="Gọi mô hình (MODEL_ONLY)" value="MODEL_ONLY" />
            <el-option label="Agent nền tảng (vòng công cụ có kiểm soát)" value="PLATFORM_AGENT" />
            <el-option label="Agent từ xa (đang lên kế hoạch)" value="REMOTE_AGENT" disabled />
          </el-select>
          <div class="form-help">Cách chạy gắn với phiên bản. Sau khi phát hành thì không sửa được.</div>
        </el-form-item>
        <div class="agent-form-grid">
          <el-form-item label="Nhà cung cấp" required
            ><el-select
              v-model="versionForm.providerId"
              filterable
              placeholder="Chọn nhà cung cấp của tổ chức này"
              :disabled="editingVersion?.status === 'PUBLISHED'"
              ><el-option
                v-for="provider in enabledProvidersQuery.data.value ?? []"
                :key="provider.id"
                :label="`${provider.name} · ${providerTypeLabel(provider.type)}`"
                :value="provider.id" /></el-select></el-form-item
          ><el-form-item label="Mô hình"
            ><el-input
              v-model="versionForm.modelName"
              :disabled="editingVersion?.status === 'PUBLISHED'"
              placeholder="Để trống để dùng mô hình mặc định của nhà cung cấp"
          /></el-form-item>
        </div>
        <el-form-item label="Prompt hệ thống" required
          ><el-input
            v-model="versionForm.systemPrompt"
            type="textarea"
            :rows="8"
            maxlength="20000"
            show-word-limit
            :disabled="editingVersion?.status === 'PUBLISHED'"
            placeholder="Nêu việc Agent làm, giới hạn, yêu cầu đầu vào và đầu ra"
        /></el-form-item>
        <div class="agent-form-grid">
          <el-form-item label="Thời gian chờ khi Agent chạy (giây)" required
            ><el-input-number
              v-model="versionForm.timeoutSeconds"
              :min="1"
              :max="3600"
              :disabled="editingVersion?.status === 'PUBLISHED'" /></el-form-item
          >          <el-form-item label="Khi Agent lỗi" required
            ><el-select
              v-model="versionForm.failurePolicy"
              :disabled="editingVersion?.status === 'PUBLISHED'"
              ><el-option label="Dừng công việc" value="FAIL_PROCESS" /><el-option
                label="Đi tiếp, không có kết quả"
                value="CONTINUE_EMPTY" /><el-option
                label="Giao cho người"
                value="MANUAL_REVIEW" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="Trường đầu vào của Agent">
          <SchemaFieldsEditor
            :fields="inputSchemaFields"
            :readonly="editingVersion?.status === 'PUBLISHED'"
            direction="input"
            @add="addInputSchemaField"
            @remove="removeInputSchemaField"
          />
          <div class="form-help">
            Trường đối tượng hoặc trường lồng nhau dùng đường dẫn có dấu chấm, ví dụ
            customer.name. Với danh sách, điền đường dẫn và chọn kiểu từng mục. Quy trình chỉ gửi dữ liệu bạn gắn rõ.
          </div>
        </el-form-item>
        <el-form-item label="Trường đầu ra của Agent">
          <SchemaFieldsEditor
            :fields="outputSchemaFields"
            :readonly="editingVersion?.status === 'PUBLISHED'"
            direction="output"
            @add="addOutputSchemaField"
            @remove="(index) => outputSchemaFields.splice(index, 1)"
          />
          <div class="form-help">
            Trường đối tượng hoặc trường lồng nhau dùng đường dẫn có dấu chấm, ví dụ
            customer.name. Với danh sách, điền đường dẫn và chọn kiểu từng mục.
          </div>
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="versionEditorVisible = false">Đóng</el-button
        ><el-button
          v-if="editingVersion?.status === 'DRAFT'"
          type="primary"
          :loading="saveVersionMutation.isPending.value"
          @click="saveVersionMutation.mutate()"
          >Lưu bản nháp</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.agent-overview > div {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border: 1px solid var(--color-border-soft);
  border-radius: 14px;
  background: var(--color-surface);
}
.agent-overview p,
.agent-name-cell p {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin: 0;
}
.agent-overview strong {
  color: var(--color-text-strong);
  font-size: 13px;
}
.agent-overview small,
.agent-name-cell small,
.cell-secondary {
  color: var(--color-text-subtle);
  font-size: 11px;
}
.overview-icon {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
}
.overview-icon.tone-purple {
  color: var(--color-agent-accent);
  background: var(--color-agent-accent-soft);
}
.overview-icon.tone-green {
  color: var(--color-success);
  background: var(--color-success-soft);
}
.overview-icon.tone-amber {
  color: var(--color-warning);
  background: var(--color-warning-soft);
}
.agent-workspace {
  height: clamp(560px, calc(100vh - 280px), 760px);
  min-height: 560px;
  overflow: hidden;
  border: 1px solid var(--color-border-soft);
  border-radius: 16px;
  background: var(--color-surface);
}
.agent-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.agent-tabs :deep(.el-tabs__content) {
  min-height: 0;
  flex: 1;
}
.agent-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.agent-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
  background: var(--color-surface-muted);
}
.agent-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--color-border-soft);
}
.agent-tabs :deep(.el-tabs__item) {
  height: 52px;
  font-weight: 650;
}
.agent-tab-content {
  height: 100%;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 14px;
  padding: 16px;
}
.agent-tab-content .page-actions {
  border: 1px solid var(--color-border-soft);
  border-radius: 13px;
  background: var(--color-surface-muted);
}
.filter-form--agent {
  flex: 1;
  grid-template-columns: minmax(220px, 1fr) minmax(150px, 0.55fr) max-content !important;
  align-items: center;
}
.filter-form--agent > .el-form-item:not(.filter-form__actions) {
  align-items: center;
}
.filter-form--agent > .el-form-item:not(.filter-form__actions) .el-form-item__label {
  min-width: 48px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  line-height: 1.4;
}
.filter-form--agent > .el-form-item:not(.filter-form__actions) .el-form-item__content {
  height: 36px;
  min-height: 36px;
  display: flex;
  align-items: center;
}
.agent-table-panel {
  min-height: 0;
  border: 1px solid var(--color-border-soft);
  border-radius: 13px;
  box-shadow: none;
}
.agent-table-panel .table-pagination {
  justify-content: flex-end;
  padding: 12px 14px;
  border-top: 1px solid var(--color-border-soft);
}
.agent-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.agent-name-cell > span {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  font-weight: 750;
}
.version-status-badge {
  width: 88px;
  min-width: 88px;
}
.agent-table-panel :deep(.agent-status-column .cell) {
  overflow: visible;
  text-overflow: clip;
  white-space: nowrap;
}
.provider-table :deep(td.el-table__cell) {
  height: 66px;
}
.credential-hint {
  display: inline-block;
  padding: 3px 6px;
  border: 1px solid var(--color-border-soft);
  border-radius: 6px;
  color: var(--color-text-muted);
  background: var(--color-surface-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1;
}
.credential-empty {
  color: var(--color-text-subtle);
}
.agent-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.manual-run-intro {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
  padding: 13px 14px;
  border: 1px solid var(--color-border-soft);
  border-radius: 12px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
}
.manual-run-intro p {
  display: grid;
  gap: 3px;
  margin: 0;
}
.state-transition {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.run-status-summary {
  gap: 8px !important;
}
.state-history-panel {
  margin-top: 12px;
  padding: 16px 12px 8px;
  border: 1px solid var(--color-border-soft);
  border-radius: 12px;
  background: var(--color-surface-muted);
}
.state-history-guide {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 4px 16px;
  color: var(--color-text-muted);
  font-size: 12px;
}
.state-history-guide strong {
  color: var(--color-text);
  font-size: 13px;
}
.state-history-guide span {
  color: var(--color-text-subtle);
}
.state-history-timeline {
  padding: 4px 8px 0;
}
.state-history-timeline :deep(.el-timeline-item:first-child) {
  padding-top: 4px;
}
.state-history-timeline :deep(.el-timeline-item__content) {
  color: var(--color-text-muted);
}
.manual-run-intro span {
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
}
.form-help {
  margin-top: 6px;
  color: var(--color-text-subtle);
  font-size: 12px;
  line-height: 1.6;
}
.run-detail {
  min-height: 280px;
}
.run-summary-card {
  display: grid;
  grid-template-columns: 1.3fr 0.75fr 1.1fr;
  gap: 22px;
  padding: 18px 20px;
  border: 1px solid var(--color-border);
  border-radius: 13px;
  background: var(--color-surface-muted);
}
.run-summary-card > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
}
.run-summary-card small,
.run-summary-card__meta,
.el-timeline-item small {
  color: var(--color-text-muted);
  font-size: 12px;
}
.run-summary-card strong,
.run-summary-card__meta {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.run-result-card {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  padding: 15px;
  border: 1px solid var(--color-border-soft);
  border-radius: 13px;
  background: var(--color-surface-muted);
}
.run-diagnostics-card {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) repeat(3, minmax(110px, 0.7fr));
  gap: 14px;
  margin-top: 12px;
  padding: 12px 15px;
  border: 1px solid var(--color-border-soft);
  border-radius: 11px;
  background: var(--color-surface-muted);
}
.run-diagnostics-card > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.run-diagnostics-card small {
  color: var(--color-text-muted);
  font-size: 12px;
}
.run-diagnostics-card strong,
.run-diagnostics-card code {
  overflow: hidden;
  color: var(--color-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.run-result-card small {
  color: var(--color-text-muted);
  font-weight: 650;
}
.run-result-card pre,
.run-payload-grid pre {
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: 9px;
  color: var(--color-text);
  background: var(--color-code-surface);
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.run-error-title {
  font-weight: 700;
}
.run-error-detail {
  display: grid;
  gap: 5px;
  line-height: 1.6;
}
.run-error-action {
  color: var(--color-text-muted);
  font-size: 12px;
}
.run-payload-grid {
  display: grid;
  gap: 12px;
}
.run-payload-grid article {
  display: grid;
  gap: 7px;
}
.run-payload-grid article > strong {
  color: var(--color-text-muted);
  font-size: 12px;
}
.run-detail-tabs {
  margin-top: 18px;
}
.checkpoint-list {
  display: grid;
  gap: 12px;
}
.checkpoint-list article {
  overflow: hidden;
  border: 1px solid var(--color-border-soft);
  border-radius: 11px;
}
.checkpoint-list header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  color: var(--color-text-muted);
  background: var(--color-surface-muted);
}
.checkpoint-list pre {
  max-height: 260px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  color: var(--color-text);
  background: var(--color-surface);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.el-timeline-item p {
  margin: 5px 0;
  color: var(--color-text-muted);
}
.version-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 11px;
  background: var(--color-surface-muted);
}
.version-toolbar p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}
@media (max-width: 1100px) {
  .agent-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .agent-overview,
  .agent-form-grid {
    grid-template-columns: 1fr;
  }
  .filter-form--agent {
    grid-template-columns: 1fr !important;
  }
  .version-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .run-summary-card {
    grid-template-columns: 1fr;
  }
  .run-diagnostics-card {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
