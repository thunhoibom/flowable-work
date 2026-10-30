<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw, Search, Trash2 } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TablePagination from '@/components/TablePagination.vue'
import MetricCard from '@/components/MetricCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import { definitionApi, type ProcessDefinition } from '@/features/process-definition'
import { useAuthStore } from '@/stores/auth'
import { listUserTasks, resolveTaskMode } from '@/utils/bpmn'
import { confirmAction } from '@/utils/confirmation'
import type { BpmnTaskMode, BpmnUserTask } from '@/utils/bpmn'
import { formatDateTime, formatVersion, joinValues } from '@/utils/format'
import { assignmentRuleApi } from '../api'
import RuleUserPickerField from '../components/RuleUserPickerField.vue'
import type {
  AssignmentRule,
  AssignmentRuleCommand,
  AssignmentType,
  EmptyUserStrategy,
  RuleOperator,
} from '../types'
import '../styles.css'

const queryClient = useQueryClient()
const authStore = useAuthStore()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const query = reactive({
  pageNum: 1,
  pageSize: 20,
  processDefinitionKey: '',
  processDefinitionId: '',
  version: undefined as number | undefined,
  taskDefinitionKey: '',
  variableName: '',
  assignmentType: undefined as AssignmentType | undefined,
  emptyUserStrategy: undefined as EmptyUserStrategy | undefined,
})
const applied = ref({ ...query })
const dialogVisible = ref(false)
const editingId = ref<number>()
const form = reactive({
  processDefinitionKey: '',
  processDefinitionId: '',
  taskDefinitionKey: '',
  priority: 100,
  assignmentType: 'ASSIGNEE' as AssignmentType,
  assignees: [] as string[],
  candidateUsers: [] as string[],
  countersignUsers: [] as string[],
  emptyUserStrategy: 'AUTO_REJECT' as EmptyUserStrategy,
  fallbackAssignees: [] as string[],
  enabled: true,
  description: '',
  conditions: [] as Array<{ variableName: string; operator: RuleOperator; variableValue: string }>,
})
const defaultAssignmentTypeByMode: Record<BpmnTaskMode, AssignmentType> = {
  single: 'ASSIGNEE',
  candidate: 'CANDIDATE_USERS',
  parallel: 'COUNTERSIGN_USERS',
}

const rulesQuery = useQuery({
  queryKey: computed(() => queryKeys.assignmentRulePage(tenantCode.value, applied.value)),
  queryFn: () => assignmentRuleApi.page(applied.value),
})
const records = computed(() => rulesQuery.data.value?.records ?? [])
const total = computed(() => rulesQuery.data.value?.total ?? 0)
const enabledCount = computed(() => records.value.filter((item) => item.enabled).length)
const disabledCount = computed(() => Math.max(records.value.length - enabledCount.value, 0))
const processCatalogQuery = useQuery({
  queryKey: computed(() => queryKeys.processDefinitionCatalog(tenantCode.value)),
  queryFn: () => definitionApi.listProcesses(),
})
const processOptions = computed(() => {
  const byKey = new Map<string, ProcessDefinition>()
  ;(processCatalogQuery.data.value ?? []).forEach((item) => {
    const current = byKey.get(item.processDefinitionKey)
    if (!current || item.version > current.version) byKey.set(item.processDefinitionKey, item)
  })
  return [...byKey.values()]
})
const versionsQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.processDefinitionVersions(tenantCode.value, form.processDefinitionKey),
  ),
  queryFn: () => definitionApi.listVersions(form.processDefinitionKey),
  enabled: computed(() => Boolean(form.processDefinitionKey)),
})
const versionsLoading = computed(
  () =>
    Boolean(form.processDefinitionKey) &&
    (versionsQuery.isPending.value || versionsQuery.isFetching.value),
)
const selectedProcessDefinition = computed(() =>
  versionsQuery.data.value?.find((item) => item.processDefinitionId === form.processDefinitionId),
)
const definitionDetailQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.processDefinitionDetail(
      tenantCode.value,
      form.processDefinitionKey,
      selectedProcessDefinition.value?.version,
    ),
  ),
  queryFn: () =>
    definitionApi.detail(form.processDefinitionKey, selectedProcessDefinition.value!.version),
  enabled: computed(
    () =>
      dialogVisible.value &&
      Boolean(form.processDefinitionKey) &&
      Boolean(selectedProcessDefinition.value?.version),
  ),
})
const availableUserTasks = computed(() => {
  const bpmnXml = definitionDetailQuery.data.value?.bpmnXml
  return bpmnXml ? listUserTasks(bpmnXml) : []
})
const tasksLoading = computed(
  () =>
    Boolean(form.processDefinitionId) &&
    (definitionDetailQuery.isPending.value || definitionDetailQuery.isFetching.value),
)

const saveMutation = useMutation({
  mutationFn: async () => {
    const command = buildCommand()
    const definition = selectedDefinition()
    if (!definition) throw new Error('Hãy chọn phiên bản quy trình hợp lệ')
    const definitionDetail =
      definitionDetailQuery.data.value ??
      (await definitionApi.detail(definition.processDefinitionKey, definition.version))
    validateCommand(command, definitionDetail)
    return editingId.value
      ? assignmentRuleApi.update(editingId.value, command)
      : assignmentRuleApi.create(command)
  },
  onSuccess: async () => {
    dialogVisible.value = false
    ElMessage.success(editingId.value ? 'Đã cập nhật phân công' : 'Đã thêm phân công')
    await queryClient.invalidateQueries({ queryKey: queryKeys.assignmentRules(tenantCode.value) })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function targetValues(rule: AssignmentRule, type: string) {
  return rule.targets
    .filter((target) => target.targetType === type)
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((target) => target.targetValue)
}
function buildCommand(): AssignmentRuleCommand {
  return {
    processDefinitionId: form.processDefinitionId,
    taskDefinitionKey: form.taskDefinitionKey.trim(),
    priority: Number(form.priority),
    assignmentType: form.assignmentType,
    assignees: form.assignees,
    candidateUsers: form.candidateUsers,
    candidateGroups: [],
    countersignUsers: form.countersignUsers,
    emptyUserStrategy: form.emptyUserStrategy,
    fallbackAssignee: form.fallbackAssignees[0],
    enabled: form.enabled,
    description: form.description.trim() || undefined,
    conditions: form.conditions
      .filter((item) => item.variableName.trim())
      .map((item, index) => ({
        variableName: item.variableName.trim(),
        operator: item.operator,
        variableValue: ['EXISTS', 'NOT_EXISTS'].includes(item.operator)
          ? ''
          : item.variableValue.trim(),
        sortOrder: (index + 1) * 10,
      })),
  }
}
function selectedDefinition() {
  return selectedProcessDefinition.value
}
function validateCommand(command: AssignmentRuleCommand, definition: ProcessDefinition) {
  if (!command.processDefinitionId || !command.taskDefinitionKey)
    throw new Error('Hãy chọn phiên bản và bước')
  const mode = resolveTaskMode(definition.bpmnXml, command.taskDefinitionKey)
  if (!mode) throw new Error('Phiên bản này không có bước đó')
  const allowed: Record<string, AssignmentType[]> = {
    single: ['ASSIGNEE'],
    candidate: ['CANDIDATE_USERS'],
    parallel: ['COUNTERSIGN_USERS'],
  }
  if (!allowed[mode]!.includes(command.assignmentType))
    throw new Error(`Bước này là ${taskModeLabel(mode)}, cách giao không khớp`)
  if (command.assignmentType === 'ASSIGNEE' && command.assignees.length !== 1)
    throw new Error('Hãy chọn một người nhận')
  if (command.assignmentType === 'CANDIDATE_USERS' && !command.candidateUsers.length)
    throw new Error('Hãy chọn người có thể nhận')
  if (command.assignmentType === 'COUNTERSIGN_USERS' && !command.countersignUsers.length)
    throw new Error('Hãy chọn người cùng xử lý')
  if (command.emptyUserStrategy === 'TO_ASSIGNEE' && !command.fallbackAssignee)
    throw new Error('Hãy chọn người nhận khi không có ai')
  for (const condition of command.conditions)
    if (!['EXISTS', 'NOT_EXISTS'].includes(condition.operator) && !condition.variableValue)
      throw new Error('Hãy nhập giá trị cho điều kiện')
}
function resetForm() {
  editingId.value = undefined
  Object.assign(form, {
    processDefinitionKey: '',
    processDefinitionId: '',
    taskDefinitionKey: '',
    priority: 100,
    assignmentType: 'ASSIGNEE',
    assignees: [],
    candidateUsers: [],
    countersignUsers: [],
    emptyUserStrategy: 'AUTO_REJECT',
    fallbackAssignees: [],
    enabled: true,
    description: '',
    conditions: [],
  })
}
function openCreate() {
  resetForm()
  dialogVisible.value = true
}
function openEdit(rule: AssignmentRule) {
  editingId.value = rule.id
  Object.assign(form, {
    processDefinitionKey: rule.processDefinitionKey,
    processDefinitionId: rule.processDefinitionId,
    taskDefinitionKey: rule.taskDefinitionKey,
    priority: rule.priority,
    assignmentType: normalizeAssignmentType(rule.assignmentType),
    assignees: targetValues(rule, 'ASSIGNEE'),
    candidateUsers: targetValues(rule, 'CANDIDATE_USER'),
    countersignUsers: targetValues(rule, 'COUNTERSIGN_USER'),
    emptyUserStrategy: rule.emptyUserStrategy,
    fallbackAssignees: targetValues(rule, 'FALLBACK_ASSIGNEE').slice(0, 1),
    enabled: rule.enabled,
    description: rule.description || '',
    conditions: rule.conditions.map((item) => ({
      variableName: item.variableName,
      operator: item.operator,
      variableValue: item.variableValue || '',
    })),
  })
  dialogVisible.value = true
}
function changeProcessDefinition() {
  form.processDefinitionId = ''
  form.taskDefinitionKey = ''
}
function changeProcessVersion() {
  form.taskDefinitionKey = ''
}
function changeTaskDefinition(taskDefinitionKey: string) {
  const task = availableUserTasks.value.find((item) => item.id === taskDefinitionKey)
  if (!task) return
  form.assignmentType = defaultAssignmentTypeByMode[task.mode]
  form.assignees = []
  form.candidateUsers = []
  form.countersignUsers = []
}
function search() {
  query.pageNum = 1
  applied.value = { ...query }
}
function reset() {
  Object.assign(query, {
    processDefinitionKey: '',
    processDefinitionId: '',
    version: undefined,
    taskDefinitionKey: '',
    variableName: '',
    assignmentType: undefined,
    emptyUserStrategy: undefined,
    pageNum: 1,
  })
  applied.value = { ...query }
}
function changePage(pageNum: number, pageSize: number) {
  query.pageNum = pageNum
  query.pageSize = pageSize
  applied.value = { ...query }
}
function addCondition() {
  form.conditions.push({ variableName: '', operator: 'EQ', variableValue: '' })
}
async function remove(rule: AssignmentRule) {
  const confirmed = await confirmAction(
    `Xóa phân công của bước ${rule.taskDefinitionKey}?`,
    'Xóa phân công',
    {
      type: 'warning',
      confirmButtonText: 'Xóa',
      cancelButtonText: 'Hủy',
    },
  )
  if (!confirmed) return
  try {
    await assignmentRuleApi.delete(rule.id)
    ElMessage.success('Đã xóa phân công')
    await queryClient.invalidateQueries({ queryKey: queryKeys.assignmentRules(tenantCode.value) })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
async function toggleEnabled(rule: AssignmentRule) {
  const command = {
    processDefinitionId: rule.processDefinitionId,
    taskDefinitionKey: rule.taskDefinitionKey,
    priority: rule.priority,
    assignmentType: rule.assignmentType,
    assignees: targetValues(rule, 'ASSIGNEE'),
    candidateUsers: targetValues(rule, 'CANDIDATE_USER'),
    candidateGroups: targetValues(rule, 'CANDIDATE_GROUP'),
    countersignUsers: targetValues(rule, 'COUNTERSIGN_USER'),
    emptyUserStrategy: rule.emptyUserStrategy,
    fallbackAssignee: targetValues(rule, 'FALLBACK_ASSIGNEE')[0],
    enabled: !rule.enabled,
    description: rule.description,
    conditions: rule.conditions.map(({ variableName, operator, variableValue, sortOrder }) => ({
      variableName,
      operator,
      variableValue,
      sortOrder,
    })),
  }
  try {
    await assignmentRuleApi.update(rule.id, command)
    ElMessage.success(command.enabled ? 'Đã bật quy tắc' : 'Đã tắt quy tắc')
    await queryClient.invalidateQueries({ queryKey: queryKeys.assignmentRules(tenantCode.value) })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
function processLabel(item: ProcessDefinition) {
  return `${item.processDefinitionName} / ${item.processDefinitionKey}`
}
function versionLabel(item: ProcessDefinition) {
  const status = item.active ? 'Đang dùng' : 'Bản cũ'
  return `${formatVersion(item.version)} · ${status} · ${formatDateTime(item.deployedAt)}`
}
function taskLabel(task: BpmnUserTask) {
  return `${task.name} / ${task.id} · ${taskModeLabel(task.mode)}`
}
function taskModeLabel(mode: BpmnTaskMode) {
  return { single: 'Một người', candidate: 'Có thể nhận', parallel: 'Cùng xử lý' }[mode]
}
function assignmentTypeLabel(type: AssignmentType) {
  return {
    ASSIGNEE: 'Một người',
    CANDIDATE_USERS: 'Có thể nhận',
    CANDIDATE_GROUPS: 'Nhóm',
    COUNTERSIGN_USERS: 'Cùng xử lý',
    MIXED: 'Có thể nhận',
  }[type]
}
function normalizeAssignmentType(type: AssignmentType): AssignmentType {
  return ['CANDIDATE_USERS', 'CANDIDATE_GROUPS', 'MIXED'].includes(type) ? 'CANDIDATE_USERS' : type
}
function conditionsText(rule: AssignmentRule) {
  return rule.conditions.length
    ? rule.conditions
        .map(
          (item) =>
            `${item.variableName} ${item.operator}${['EXISTS', 'NOT_EXISTS'].includes(item.operator) ? '' : ` ${item.variableValue}`}`,
        )
        .join(' và ')
    : 'Không điều kiện'
}
function assignmentTargetsText(rule: AssignmentRule) {
  return joinValues(
    rule.targets
      .filter((item) => item.targetType !== 'FALLBACK_ASSIGNEE')
      .map((item) => item.targetValue),
  )
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Giao việc"
      title="Phân công"
      description="Chọn người nhận việc cho từng bước trong quy trình."
    />

    <section class="metric-grid">
      <MetricCard :value="total" label="Quy tắc"><template #icon>Σ</template></MetricCard>
      <MetricCard :value="enabledCount" label="Đang dùng" tone="success">
        <template #icon>✓</template>
      </MetricCard>
      <MetricCard :value="disabledCount" label="Đã tắt" tone="warning">
        <template #icon>–</template>
      </MetricCard>
    </section>

    <section class="page-actions compact-filter">
      <el-form class="filter-form filter-form--assignment" inline @submit.prevent="search"
        ><el-form-item label="Mã quy trình"
          ><el-input v-model="query.processDefinitionKey" clearable /></el-form-item
        ><el-form-item label="Phiên bản"
          ><el-input-number
            v-model="query.version"
            :min="1"
            controls-position="right" /></el-form-item
        ><el-form-item label="Bước"
          ><el-input v-model="query.taskDefinitionKey" clearable /></el-form-item
        ><el-form-item label="Tên dữ liệu"
          ><el-input v-model="query.variableName" clearable /></el-form-item
        ><el-form-item label="Cách giao"
          ><el-select v-model="query.assignmentType" clearable
            ><el-option label="Một người" value="ASSIGNEE" /><el-option
              label="Có thể nhận"
              value="CANDIDATE_USERS" /><el-option
              label="Cùng xử lý"
              value="COUNTERSIGN_USERS" /></el-select></el-form-item
        ><el-form-item class="filter-form__actions"
          ><el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button
          ><el-button @click="reset"><RefreshCw :size="16" />Đặt lại</el-button></el-form-item
        ></el-form
      ><el-button class="page-primary-action" type="primary" @click="openCreate"
        ><Plus :size="17" />Thêm quy tắc</el-button
      >
    </section>
    <section class="table-panel">
      <el-table v-loading="rulesQuery.isFetching.value" :data="records" height="100%"
        ><el-table-column
          prop="processDefinitionKey"
          label="Mã quy trình"
          min-width="160" /><el-table-column label="Phiên bản" width="110"
          ><template #default="{ row }">{{ formatVersion(row.version) }}</template></el-table-column
        ><el-table-column
          prop="taskDefinitionKey"
          label="Bước"
          min-width="160" /><el-table-column
          label="Điều kiện"
          min-width="260"
          show-overflow-tooltip
          ><template #default="{ row }">{{ conditionsText(row) }}</template></el-table-column
        ><el-table-column prop="priority" label="Độ ưu tiên" width="85" /><el-table-column
          label="Cách giao"
          width="110"
          ><template #default="{ row }">{{
            assignmentTypeLabel(row.assignmentType)
          }}</template></el-table-column
        ><el-table-column label="Đích" min-width="200" show-overflow-tooltip
          ><template #default="{ row }">{{
            assignmentTargetsText(row) || '-'
          }}</template></el-table-column
        ><el-table-column label="Bật" width="80"
          ><template #default="{ row }"
            ><el-switch
              :model-value="row.enabled"
              @change="toggleEnabled(row)" /></template></el-table-column
        ><el-table-column label="Thời điểm cập nhật" width="175"
          ><template #default="{ row }">{{
            formatDateTime(row.updatedAt)
          }}</template></el-table-column
        ><el-table-column label="Thao tác" width="130" fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="openEdit(row)">Sửa</el-button
            ><el-button link type="danger" @click="remove(row)"
              ><Trash2 :size="14" />Xóa</el-button
            ></template
          ></el-table-column
        ><template #empty>
          <ListEmptyState
            title="Chưa có quy tắc"
            description="Khi chưa có quy tắc, hệ thống dùng người được chọn lúc bắt đầu hoặc lúc duyệt."
          /> </template></el-table
      ><TablePagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        aria-label="Phân trang phân công"
        @change="changePage"
      />
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? 'Sửa phân công' : 'Thêm phân công'"
      width="900px"
      destroy-on-close
    >
      <el-form class="rule-form" label-position="top">
        <el-alert
          v-if="editingId"
          title="Vẫn sửa được quy tắc của phiên bản cũ. Không đổi quy trình, phiên bản và bước đã gắn."
          type="info"
          :closable="false"
          show-icon
          class="rule-binding-alert"
        />
        <div class="form-grid">
          <el-form-item label="Quy trình" required
            ><el-select
              v-model="form.processDefinitionKey"
              class="assignment-rule-select"
              :class="{ 'has-value': Boolean(form.processDefinitionKey) }"
              filterable
              :loading="processCatalogQuery.isFetching.value"
              :disabled="Boolean(editingId) || processCatalogQuery.isFetching.value"
              loading-text="Đang tải quy trình"
              no-data-text="Tổ chức này chưa có quy trình"
              placeholder="Chọn quy trình"
              @change="changeProcessDefinition"
              ><el-option
                v-for="item in processOptions"
                :key="item.processDefinitionKey"
                :label="processLabel(item)"
                :value="item.processDefinitionKey" /></el-select></el-form-item
          ><el-form-item label="Phiên bản" required
            ><el-select
              v-model="form.processDefinitionId"
              class="assignment-rule-select"
              :class="{ 'has-value': Boolean(form.processDefinitionId) }"
              filterable
              :loading="versionsLoading"
              :disabled="Boolean(editingId) || !form.processDefinitionKey || versionsLoading"
              loading-text="Đang tải phiên bản"
              no-data-text="Quy trình này chưa có phiên bản"
              :placeholder="versionsLoading ? 'Đang tải phiên bản' : 'Chọn phiên bản'"
              @change="changeProcessVersion"
              ><el-option
                v-for="item in versionsQuery.data.value ?? []"
                :key="item.processDefinitionId"
                :label="versionLabel(item)"
                :value="item.processDefinitionId" /></el-select></el-form-item
          ><el-form-item label="Bước" required
            ><el-select
              v-model="form.taskDefinitionKey"
              class="assignment-rule-select"
              :class="{ 'has-value': Boolean(form.taskDefinitionKey) }"
              filterable
              :loading="tasksLoading"
              :disabled="Boolean(editingId) || !form.processDefinitionId || tasksLoading"
              loading-text="Đang tải các bước"
              :placeholder="tasksLoading ? 'Đang tải các bước' : 'Chọn bước'"
              no-data-text="Phiên bản này chưa có bước cần người"
              @change="changeTaskDefinition"
              ><el-option
                v-for="task in availableUserTasks"
                :key="task.id"
                :label="taskLabel(task)"
                :value="task.id"
                ><div class="task-option">
                  <span>{{ task.name }}</span>
                  <small>{{ task.id }} · {{ taskModeLabel(task.mode) }}</small>
                </div></el-option
              ></el-select
            ></el-form-item
          ><el-form-item label="Độ ưu tiên"
            ><el-input-number v-model="form.priority" :min="0" :max="9999" /></el-form-item
          ><el-form-item label="Cách giao" required
            ><div
              class="assignment-type-indicator"
              :class="{ 'is-empty': !form.taskDefinitionKey }"
            >
              <span class="assignment-type-indicator__dot" aria-hidden="true" />
              <strong>{{
                form.taskDefinitionKey ? assignmentTypeLabel(form.assignmentType) : 'Chưa rõ'
              }}</strong>
              <span class="assignment-type-indicator__hint">{{
                form.taskDefinitionKey ? 'Theo bước đã chọn' : 'Hãy chọn bước trước'
              }}</span>
            </div></el-form-item
          >
        </div>
        <el-form-item
          v-if="form.taskDefinitionKey && form.assignmentType === 'ASSIGNEE'"
          label="Người nhận"
          required
          ><RuleUserPickerField
            v-model="form.assignees"
            title="Chọn người nhận"
            :multiple="false"
            empty-text="Chưa chọn người" /></el-form-item
        ><el-form-item
          v-if="form.taskDefinitionKey && form.assignmentType === 'CANDIDATE_USERS'"
          label="Người có thể nhận"
          required
          ><RuleUserPickerField
            v-model="form.candidateUsers"
            title="Chọn người có thể nhận"
            multiple
            empty-text="Chưa chọn người" /></el-form-item
        ><el-form-item
          v-if="form.taskDefinitionKey && form.assignmentType === 'COUNTERSIGN_USERS'"
          label="Cùng xử lý"
          required
          ><RuleUserPickerField
            v-model="form.countersignUsers"
            title="Chọn người cùng xử lý"
            multiple
            empty-text="Chưa chọn người"
        /></el-form-item>
        <div class="subsection-heading">
          <h3>Khi nào áp dụng</h3>
          <el-button link type="primary" @click="addCondition"
            ><Plus :size="15" />Thêm điều kiện</el-button
          >
        </div>
        <div v-for="(condition, index) in form.conditions" :key="index" class="condition-row">
          <el-input v-model="condition.variableName" placeholder="Tên dữ liệu" /><el-select
            v-model="condition.operator"
            ><el-option
              v-for="operator in [
                'EQ',
                'NE',
                'EXISTS',
                'NOT_EXISTS',
                'IN',
                'NOT_IN',
                'GT',
                'GE',
                'LT',
                'LE',
              ]"
              :key="operator"
              :label="operator"
              :value="operator" /></el-select
          ><el-input
            v-model="condition.variableValue"
            :disabled="['EXISTS', 'NOT_EXISTS'].includes(condition.operator)"
            placeholder="Giá trị so sánh"
          /><el-button
            text
            type="danger"
            aria-label="Xóa điều kiện"
            @click="form.conditions.splice(index, 1)"
            ><Trash2 :size="16"
          /></el-button>
        </div>
        <div class="form-grid">
          <el-form-item label="Khi không có ai"
            ><el-select v-model="form.emptyUserStrategy"
              ><el-option label="Giao cho người khác" value="TO_ASSIGNEE" /><el-option
                label="Đánh dấu xong"
                value="AUTO_COMPLETE" /><el-option
                label="Tự trả lại"
                value="AUTO_REJECT" /></el-select></el-form-item
          ><el-form-item v-if="form.emptyUserStrategy === 'TO_ASSIGNEE'" label="Người nhận thay" required
            ><RuleUserPickerField
              v-model="form.fallbackAssignees"
              title="Chọn người nhận thay"
              :multiple="false"
              empty-text="Chưa chọn người" /></el-form-item
          ><el-form-item label="Bật"><el-switch v-model="form.enabled" /></el-form-item>
        </div>
        <el-form-item label="Mô tả">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="saveMutation.isPending.value"
          :disabled="
            !form.processDefinitionId ||
            !form.taskDefinitionKey ||
            versionsQuery.isFetching.value ||
            definitionDetailQuery.isFetching.value
          "
          @click="saveMutation.mutate()"
          >Lưu</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>
