<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Ban, Check, RefreshCw, RotateCcw, Send } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TablePagination from '@/components/TablePagination.vue'
import { canOperateInstances as isInstanceOperable } from '@/features/auth/authorization'
import { useAuthStore } from '@/stores/auth'
import { promptRequired } from '@/utils/confirmation'
import {
  displayValue,
  formatDateTime,
  formatDuration,
  formatVersion,
  joinValues,
  splitValues,
} from '@/utils/format'
import ProcessDiagram from '../components/ProcessDiagram.vue'
import InteractionDataFields from '../components/InteractionDataFields.vue'
import ParticipantAssignmentEditor from '../components/ParticipantAssignmentEditor.vue'
import { processInstanceApi } from '../api'
import {
  buildInteractionVariables,
  synchronizeInteractionValues,
  type InteractionValues,
} from '../interaction'
import type {
  InteractionDataField,
  ParticipantAssignment,
  ParticipantRequirement,
  TaskItem,
} from '../types'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const authStore = useAuthStore()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canOperateInstances = computed(() => isInstanceOperable(authStore.user))
const id = computed(() => String(route.params.id))
const activeTab = ref('diagram')
const taskPage = reactive({ pageNum: 1, pageSize: 10 })
const variablePage = reactive({ pageNum: 1, pageSize: 10 })
const transferVisible = ref(false)
const decisionVisible = ref(false)
const decisionType = ref<'approve' | 'reject'>('approve')
const selectedTask = ref<TaskItem>()
const decisionForm = reactive({ comment: '' })
const decisionRequirements = ref<ParticipantRequirement[]>([])
const decisionAssignments = ref<ParticipantAssignment[]>([])
const decisionInteractionFields = ref<InteractionDataField[]>([])
const decisionInteractionAgentActivityIds = ref<string[]>([])
const decisionInteractionValues = ref<InteractionValues>({})
const decisionContextLoading = ref(false)
const transferForm = reactive({
  targetAssignee: '',
  targetCandidateUsers: '',
  targetCandidateGroups: '',
  comment: '',
})

const detailQuery = useQuery({
  queryKey: computed(() => queryKeys.processInstanceDetail(tenantCode.value, id.value)),
  queryFn: () => processInstanceApi.detail(id.value),
})
const diagramQuery = useQuery({
  queryKey: computed(() => queryKeys.processInstanceDiagram(tenantCode.value, id.value)),
  queryFn: () => processInstanceApi.diagram(id.value),
})
const instance = computed(() => detailQuery.data.value?.instance)
const visibleTasks = computed(() => {
  const start = (taskPage.pageNum - 1) * taskPage.pageSize
  return (detailQuery.data.value?.tasks ?? []).slice(start, start + taskPage.pageSize)
})
const visibleVariables = computed(() => {
  const start = (variablePage.pageNum - 1) * variablePage.pageSize
  return (detailQuery.data.value?.variables ?? []).slice(start, start + variablePage.pageSize)
})

const terminateMutation = useMutation({
  mutationFn: (reason: string) => processInstanceApi.terminate(id.value, reason),
  onSuccess: async () => {
    ElMessage.success('Đã kết thúc công việc')
    await refresh()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const transferMutation = useMutation({
  mutationFn: () => {
    const task = selectedTask.value
    if (!task || !canActOnTask(task)) throw new Error('Người dùng hiện tại không thao tác được nhiệm vụ này')
    const targetCount = [
      transferForm.targetAssignee,
      transferForm.targetCandidateUsers,
      transferForm.targetCandidateGroups,
    ].filter((value) => value.trim()).length
    if (targetCount !== 1) throw new Error('Chỉ được điền đúng một đích chuyển giao')
    return processInstanceApi.transfer({
      taskId: task.taskId,
      currentAssignee: task.assignee || undefined,
      currentCandidateGroups: task.candidateGroups || [],
      targetAssignee: transferForm.targetAssignee.trim() || undefined,
      targetCandidateUsers: splitValues(transferForm.targetCandidateUsers),
      targetCandidateGroups: splitValues(transferForm.targetCandidateGroups),
      comment: transferForm.comment.trim() || undefined,
    })
  },
  onSuccess: async () => {
    transferVisible.value = false
    ElMessage.success('Đã chuyển giao nhiệm vụ')
    await refresh()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const decisionMutation = useMutation({
  mutationFn: () => {
    const task = selectedTask.value
    if (!task || !canActOnTask(task)) throw new Error('Người dùng hiện tại không thao tác được nhiệm vụ này')
    const variables = buildInteractionVariables(
      decisionInteractionFields.value,
      decisionInteractionValues.value,
    )
    const base = {
      taskId: task.taskId,
      currentAssignee: task.assignee || undefined,
      currentCandidateGroups: task.candidateGroups || [],
      comment: decisionForm.comment.trim() || undefined,
      variables,
      participantAssignments: decisionAssignments.value,
    }
    return decisionType.value === 'approve'
      ? processInstanceApi.approve(base)
      : processInstanceApi.reject({
          ...base,
          targetAssignees: [],
          targetCandidateGroups: [],
        })
  },
  onSuccess: async () => {
    decisionVisible.value = false
    ElMessage.success(decisionType.value === 'approve' ? 'Đã đồng ý' : 'Đã trả lại')
    await refresh()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
const decisionRequirementsMutation = useMutation({
  mutationFn: (variables: Record<string, unknown> = {}) => {
    const task = selectedTask.value
    if (!task) throw new Error('Hãy chọn nhiệm vụ')
    return processInstanceApi.taskParticipantRequirements({
      taskId: task.taskId,
      action: decisionType.value === 'approve' ? 'APPROVE' : 'REJECT',
      variables,
    })
  },
  onSuccess: (requirements) => {
    decisionRequirements.value = requirements
    const activityIds = new Set(requirements.map((item) => item.activityId))
    decisionAssignments.value = decisionAssignments.value.filter((item) =>
      activityIds.has(item.activityId),
    )
  },
})

async function refresh() {
  await Promise.all([detailQuery.refetch(), diagramQuery.refetch()])
  await queryClient.invalidateQueries({ queryKey: queryKeys.processInstances(tenantCode.value) })
}
async function terminate() {
  const reason = await promptRequired('Hãy nhập lý do. Không hoàn tác được.', 'Kết thúc công việc', {
    confirmButtonText: 'Kết thúc',
    cancelButtonText: 'Hủy',
    inputPattern: /\S+/,
    inputErrorMessage: 'Lý do kết thúc không được để trống',
  })
  if (!reason) return
  terminateMutation.mutate(reason)
}
function openTransfer(task: TaskItem) {
  selectedTask.value = task
  Object.assign(transferForm, {
    targetAssignee: '',
    targetCandidateUsers: '',
    targetCandidateGroups: '',
    comment: '',
  })
  transferVisible.value = true
}
function isActiveTask(task: TaskItem) {
  return ['ACTIVE', 'RUNNING'].includes(task.status)
}
function canActOnTask(task: TaskItem) {
  const username = authStore.user?.username
  return Boolean(
    canOperateInstances.value &&
    username &&
    (task.assignee === username || task.candidateUsers?.includes(username)),
  )
}
async function openDecision(task: TaskItem, type: 'approve' | 'reject') {
  selectedTask.value = task
  decisionType.value = type
  decisionForm.comment = ''
  decisionRequirements.value = []
  decisionAssignments.value = []
  decisionInteractionFields.value = []
  decisionInteractionAgentActivityIds.value = []
  decisionInteractionValues.value = {}
  decisionVisible.value = true
  decisionContextLoading.value = true
  try {
    if (type === 'approve') {
      const interaction = await processInstanceApi.taskInteraction({
        taskId: task.taskId,
        variables: {},
      })
      if (selectedTask.value?.taskId !== task.taskId || !decisionVisible.value) return
      decisionInteractionFields.value = interaction.fields
      decisionInteractionAgentActivityIds.value = interaction.agentActivityIds
      decisionInteractionValues.value = synchronizeInteractionValues(interaction.fields, {})
    }
    await decisionRequirementsMutation.mutateAsync({})
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    decisionContextLoading.value = false
  }
}
async function confirmDecision() {
  let variables: Record<string, unknown>
  try {
    variables = buildInteractionVariables(
      decisionInteractionFields.value,
      decisionInteractionValues.value,
    )
    await decisionRequirementsMutation.mutateAsync(variables)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    return
  }
  const complete = decisionRequirements.value.every((requirement) => {
    if (!requirement.required) return true
    const users = decisionAssignments.value.find(
      (item) => item.activityId === requirement.activityId,
    )?.usernames
    return requirement.multiple ? Boolean(users?.length) : users?.length === 1
  })
  if (!complete) {
    ElMessage.warning('Hãy chọn người cho mọi bước bắt buộc')
    return
  }
  decisionMutation.mutate()
}
</script>

<template>
  <div class="detail-page" v-loading="detailQuery.isFetching.value">
    <header class="detail-header">
      <button
        class="icon-button"
        type="button"
        aria-label="Về danh sách công việc"
        @click="router.push('/process-instances')"
      >
        <ArrowLeft :size="18" />
      </button>
      <div>
        <h2>{{ instance?.processDefinitionName || 'Công việc' }}</h2>
        <p>{{ id }}</p>
      </div>
      <StatusBadge :status="instance?.status === 'RUNNING' ? 'RUNNING' : 'COMPLETED'" />
      <div class="toolbar-spacer" />
      <el-button @click="refresh"><RefreshCw :size="16" />Làm mới</el-button>
      <el-button
        v-if="canOperateInstances && instance?.status === 'RUNNING'"
        type="danger"
        plain
        @click="terminate"
        ><Ban :size="16" />Kết thúc</el-button
      >
    </header>
    <section class="instance-summary">
      <div>
        <span>Mã hồ sơ</span><strong>{{ instance?.businessKey || '-' }}</strong>
      </div>
      <div>
        <span>Phiên bản quy trình</span
        ><strong>{{ formatVersion(instance?.processDefinitionVersion) }}</strong>
      </div>
      <div>
        <span>Người bắt đầu</span><strong>{{ instance?.startUserId || '-' }}</strong>
      </div>
      <div>
        <span>Thời điểm bắt đầu</span><strong>{{ formatDateTime(instance?.startTime) }}</strong>
      </div>
      <div>
        <span>Thời điểm kết thúc</span><strong>{{ formatDateTime(instance?.endTime) }}</strong>
      </div>
      <div>
        <span>Tổng thời lượng</span><strong>{{ formatDuration(instance?.durationInMillis) }}</strong>
      </div>
    </section>
    <section class="detail-content">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Tiến độ" name="diagram"
          ><ProcessDiagram v-if="activeTab === 'diagram'" :data="diagramQuery.data.value"
        /></el-tab-pane>
        <el-tab-pane :label="`Nhiệm vụ (${detailQuery.data.value?.tasks.length || 0})`" name="tasks">
          <el-table :data="visibleTasks" height="470"
            ><el-table-column prop="taskName" label="Nhiệm vụ" min-width="150" /><el-table-column
              prop="taskDefinitionKey"
              label="Bước"
              min-width="150" /><el-table-column
              prop="assignee"
              label="Người xử lý"
              width="130" /><el-table-column label="Có thể nhận" min-width="200"
              ><template #default="{ row }">{{
                joinValues([...(row.candidateUsers || []), ...(row.candidateGroups || [])]) || '-'
              }}</template></el-table-column
            ><el-table-column label="Trạng thái" width="100"
              ><template #default="{ row }"
                ><StatusBadge :status="row.status" /></template></el-table-column
            ><el-table-column label="Thời điểm bắt đầu" width="175"
              ><template #default="{ row }">{{
                formatDateTime(row.startTime)
              }}</template></el-table-column
            ><el-table-column label="Thời lượng" width="130"
              ><template #default="{ row }">{{
                formatDuration(row.durationInMillis)
              }}</template></el-table-column
            ><el-table-column v-if="canOperateInstances" label="Thao tác" width="250" fixed="right"
              ><template #default="{ row }">
                <el-tooltip
                  :disabled="canActOnTask(row)"
                  content="Chỉ người được giao hoặc người có thể nhận mới làm được việc này"
                >
                  <span class="row-actions">
                    <el-button
                      link
                      type="success"
                      :disabled="!isActiveTask(row) || !canActOnTask(row)"
                      @click="openDecision(row, 'approve')"
                      ><Check :size="15" />Đồng ý</el-button
                    >
                    <el-button
                      link
                      type="warning"
                      :disabled="!isActiveTask(row) || !canActOnTask(row)"
                      @click="openDecision(row, 'reject')"
                      ><RotateCcw :size="15" />Trả lại</el-button
                    >
                    <el-button
                      link
                      type="primary"
                      :disabled="!isActiveTask(row) || !canActOnTask(row)"
                      @click="openTransfer(row)"
                      ><Send :size="15" />Chuyển giao</el-button
                    >
                  </span>
                </el-tooltip>
              </template></el-table-column
            ><template #empty>
              <ListEmptyState
                compact
                title="Chưa có nhiệm vụ"
                description="Khi quy trình tới bước cần người, việc hiện ở đây."
              /> </template
          ></el-table>
          <TablePagination
            v-model:current-page="taskPage.pageNum"
            v-model:page-size="taskPage.pageSize"
            :total="detailQuery.data.value?.tasks.length ?? 0"
            :page-sizes="[10, 20, 50]"
            aria-label="Phân trang nhiệm vụ"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="`Dữ liệu (${detailQuery.data.value?.variables.length || 0})`"
          name="variables"
        >
          <el-table :data="visibleVariables" height="470"
            ><el-table-column prop="variableName" label="Tên" min-width="170" /><el-table-column
              prop="variableTypeName"
              label="Loại"
              width="130" /><el-table-column label="Giá trị" min-width="260" show-overflow-tooltip
              ><template #default="{ row }"
                ><code>{{ displayValue(row.value) }}</code></template
              ></el-table-column
            ><el-table-column prop="taskId" label="Gắn với nhiệm vụ" min-width="180" /><el-table-column
              label="Thời điểm cập nhật"
              width="175"
              ><template #default="{ row }">{{
                formatDateTime(row.lastUpdatedTime || row.createTime)
              }}</template></el-table-column
            ><template #empty>
              <ListEmptyState
                compact
                title="Chưa có dữ liệu"
                description="Dữ liệu phát sinh khi công việc chạy sẽ hiện ở đây."
              /> </template
          ></el-table>
          <TablePagination
            v-model:current-page="variablePage.pageNum"
            v-model:page-size="variablePage.pageSize"
            :total="detailQuery.data.value?.variables.length ?? 0"
            :page-sizes="[10, 20, 50]"
            aria-label="Phân trang dữ liệu"
          />
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog
      v-model="decisionVisible"
      :title="decisionType === 'approve' ? 'Đồng ý nhiệm vụ' : 'Trả lại nhiệm vụ'"
      width="min(720px, calc(100vw - 32px))"
      destroy-on-close
    >
      <el-alert
        v-if="decisionType === 'reject'"
        type="warning"
        :closable="false"
        title="Việc sẽ trả về bước cần người đầu tiên."
      />
      <el-form class="dialog-form" label-position="top">
        <el-form-item label="Ghi chú">
          <el-input
            v-model="decisionForm.comment"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="Không bắt buộc"
          />
        </el-form-item>
        <InteractionDataFields
          v-if="decisionType === 'approve'"
          v-model="decisionInteractionValues"
          :fields="decisionInteractionFields"
          :agent-activity-ids="decisionInteractionAgentActivityIds"
          :loading="decisionContextLoading"
        />
      </el-form>
      <div v-loading="decisionRequirementsMutation.isPending.value || decisionContextLoading">
        <ParticipantAssignmentEditor
          v-model="decisionAssignments"
          :requirements="decisionRequirements"
        />
      </div>
      <template #footer>
        <el-button @click="decisionVisible = false">Hủy</el-button>
        <el-button
          :type="decisionType === 'approve' ? 'success' : 'warning'"
          :disabled="decisionRequirementsMutation.isPending.value || decisionContextLoading"
          :loading="
            decisionMutation.isPending.value || decisionRequirementsMutation.isPending.value
          "
          @click="confirmDecision"
        >
          {{ decisionType === 'approve' ? 'Đồng ý' : 'Trả lại' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferVisible" title="Chuyển việc" width="min(560px, calc(100vw - 32px))">
      <el-alert
        type="info"
        :closable="false"
        title="Chọn một người nhận, hoặc một nhóm có thể nhận."
      />
      <el-form class="dialog-form" label-position="top">
        <el-form-item label="Người nhận"
          ><el-input v-model="transferForm.targetAssignee" placeholder="Tài khoản"
        /></el-form-item>
        <el-form-item label="Người có thể nhận"
          ><el-input
            v-model="transferForm.targetCandidateUsers"
            type="textarea"
            :rows="2"
            placeholder="Nhiều tài khoản, cách nhau bởi dấu phẩy hoặc xuống dòng"
        /></el-form-item>
        <el-form-item label="Nhóm có thể nhận"
          ><el-input
            v-model="transferForm.targetCandidateGroups"
            type="textarea"
            :rows="2"
            placeholder="Nhiều nhóm, cách nhau bởi dấu phẩy hoặc xuống dòng"
        /></el-form-item>
        <el-form-item label="Ghi chú"
          ><el-input
            v-model="transferForm.comment"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="transferVisible = false">Hủy</el-button
        ><el-button
          type="primary"
          :loading="transferMutation.isPending.value"
          @click="transferMutation.mutate()"
          >Chuyển</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>
