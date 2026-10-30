<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus, Trash2 } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import { definitionApi, type ProcessDefinition } from '@/features/process-definition'
import { useAuthStore } from '@/stores/auth'
import { formatVersion } from '@/utils/format'
import { processInstanceApi } from '../api'
import InteractionDataFields from './InteractionDataFields.vue'
import ParticipantAssignmentEditor from './ParticipantAssignmentEditor.vue'
import {
  buildInteractionVariables,
  mergeInteractionVariables,
  synchronizeInteractionValues,
  type InteractionValues,
} from '../interaction'
import {
  buildProcessVariables,
  type ProcessVariableDraft,
  type ProcessVariableType,
} from '../startProcess'
import type {
  InteractionDataField,
  ParticipantAssignment,
  ParticipantRequirement,
  StartProcessResult,
} from '../types'

const props = defineProps<{
  modelValue: boolean
  initialProcessDefinitionKey?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  started: [result: StartProcessResult]
}>()

const queryClient = useQueryClient()
const authStore = useAuthStore()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const form = reactive({ processDefinitionId: '', businessKey: '' })
const variableRows = ref<ProcessVariableDraft[]>([])
const participantRequirements = ref<ParticipantRequirement[]>([])
const participantAssignments = ref<ParticipantAssignment[]>([])
const interactionFields = ref<InteractionDataField[]>([])
const interactionAgentActivityIds = ref<string[]>([])
const interactionValues = ref<InteractionValues>({})
const interactionLoading = ref(false)
const preparedVariables = ref<Record<string, unknown>>()
const requirementsLoaded = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | undefined
let previewGeneration = 0

const definitionsQuery = useQuery({
  queryKey: computed(() => queryKeys.processDefinitionCatalog(tenantCode.value)),
  queryFn: () => definitionApi.listProcesses(),
  enabled: computed(() => props.modelValue),
})

const activeDefinitions = computed(() => {
  const definitions = (definitionsQuery.data.value ?? []).filter((item) => item.active)
  const byKey = new Map<string, ProcessDefinition>()
  definitions.forEach((item) => {
    const current = byKey.get(item.processDefinitionKey)
    if (!current || item.version > current.version) byKey.set(item.processDefinitionKey, item)
  })
  return [...byKey.values()].sort((left, right) =>
    left.processDefinitionName.localeCompare(right.processDefinitionName, 'vi'),
  )
})

const selectedDefinition = computed(() =>
  activeDefinitions.value.find((item) => item.processDefinitionId === form.processDefinitionId),
)

watch(
  [() => props.modelValue, activeDefinitions, () => props.initialProcessDefinitionKey],
  ([visible]) => {
    if (!visible || form.processDefinitionId) return
    const preferred = activeDefinitions.value.find(
      (item) => item.processDefinitionKey === props.initialProcessDefinitionKey,
    )
    if (preferred) form.processDefinitionId = preferred.processDefinitionId
    else if (activeDefinitions.value.length === 1)
      form.processDefinitionId = activeDefinitions.value[0]!.processDefinitionId
  },
  { immediate: true },
)

const startMutation = useMutation({
  mutationFn: (payload: {
    variables: Record<string, unknown>
    participantAssignments: ParticipantAssignment[]
  }) => {
    const definition = selectedDefinition.value
    if (!definition) throw new Error('Hãy chọn một quy trình')
    return processInstanceApi.start({
      processDefinitionKey: definition.processDefinitionKey,
      processDefinitionId: definition.processDefinitionId,
      businessKey: form.businessKey.trim() || undefined,
      variables: payload.variables,
      participantAssignments: payload.participantAssignments,
    })
  },
  onSuccess: async (result) => {
    ElMessage.success('Đã bắt đầu công việc')
    emit('update:modelValue', false)
    await queryClient.invalidateQueries({ queryKey: queryKeys.processInstances(tenantCode.value) })
    emit('started', result)
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

type RequirementsPreview = {
  definition: ProcessDefinition
  generation: number
  startWhenReady: boolean
  variables: Record<string, unknown>
}

const requirementsMutation = useMutation({
  mutationFn: async (preview: RequirementsPreview) => ({
    preview,
    requirements: await processInstanceApi.startParticipantRequirements({
      processDefinitionKey: preview.definition.processDefinitionKey,
      processDefinitionId: preview.definition.processDefinitionId,
      variables: preview.variables,
    }),
  }),
  onSuccess: ({ preview, requirements }) => {
    if (preview.generation !== previewGeneration) return
    preparedVariables.value = preview.variables
    participantRequirements.value = requirements
    const activityIds = new Set(requirements.map((requirement) => requirement.activityId))
    participantAssignments.value = participantAssignments.value.filter((assignment) =>
      activityIds.has(assignment.activityId),
    )
    requirementsLoaded.value = true
    if (preview.startWhenReady) continueStart()
  },
  onError: (error, preview) => {
    if (preview.generation !== previewGeneration) return
    requirementsLoaded.value = false
    if (preview.startWhenReady) ElMessage.error(getErrorMessage(error))
  },
})

async function requestRequirements(startWhenReady: boolean) {
  const definition = selectedDefinition.value
  if (!definition) {
    if (startWhenReady) ElMessage.warning('Hãy chọn một quy trình')
    return
  }
  let manualVariables: Record<string, unknown>
  try {
    manualVariables = buildProcessVariables(variableRows.value)
  } catch (error) {
    if (startWhenReady) ElMessage.error(getErrorMessage(error))
    return
  }
  const generation = ++previewGeneration
  requirementsLoaded.value = false
  interactionLoading.value = true
  try {
    const interaction = await processInstanceApi.startInteraction({
      processDefinitionKey: definition.processDefinitionKey,
      processDefinitionId: definition.processDefinitionId,
      variables: manualVariables,
    })
    if (generation !== previewGeneration) return
    interactionFields.value = interaction.fields
    interactionAgentActivityIds.value = interaction.agentActivityIds
    interactionValues.value = synchronizeInteractionValues(
      interaction.fields,
      interactionValues.value,
    )
  } catch (error) {
    if (generation === previewGeneration && startWhenReady) ElMessage.error(getErrorMessage(error))
    return
  } finally {
    if (generation === previewGeneration) interactionLoading.value = false
  }
  let variables: Record<string, unknown>
  try {
    variables = currentVariables()
  } catch (error) {
    if (startWhenReady) ElMessage.error(getErrorMessage(error))
    return
  }
  requirementsMutation.mutate({ definition, generation, startWhenReady, variables })
}

function scheduleRequirementsPreview(resetInteraction: boolean) {
  previewGeneration += 1
  requirementsLoaded.value = false
  preparedVariables.value = undefined
  participantRequirements.value = []
  participantAssignments.value = []
  if (resetInteraction) {
    interactionFields.value = []
    interactionAgentActivityIds.value = []
    interactionValues.value = {}
  }
  if (previewTimer) clearTimeout(previewTimer)
  if (!props.modelValue || !selectedDefinition.value) return
  previewTimer = setTimeout(() => requestRequirements(false), 300)
}

watch(
  () => form.processDefinitionId,
  () => scheduleRequirementsPreview(true),
)
watch(
  () => JSON.stringify(variableRows.value),
  () => scheduleRequirementsPreview(false),
)

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer)
})

function addVariable() {
  variableRows.value.push({ name: '', type: 'string', value: '' })
}

function removeVariable(index: number) {
  variableRows.value.splice(index, 1)
}

function resetForm() {
  form.processDefinitionId = ''
  form.businessKey = ''
  variableRows.value = []
  participantRequirements.value = []
  participantAssignments.value = []
  interactionFields.value = []
  interactionAgentActivityIds.value = []
  interactionValues.value = {}
  preparedVariables.value = undefined
  requirementsLoaded.value = false
  previewGeneration += 1
  if (previewTimer) clearTimeout(previewTimer)
}

function continueStart() {
  let variables: Record<string, unknown>
  try {
    variables = currentVariables()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    return
  }
  if (!participantRequirements.value.length) {
    startMutation.mutate({
      variables,
      participantAssignments: [],
    })
    return
  }
  const complete = participantRequirements.value.every((requirement) => {
    if (!requirement.required) return true
    const users = participantAssignments.value.find(
      (item) => item.activityId === requirement.activityId,
    )?.usernames
    return requirement.multiple ? Boolean(users?.length) : users?.length === 1
  })
  if (!complete) {
    ElMessage.warning('Hãy chọn người cho mọi bước bắt buộc')
    return
  }
  startMutation.mutate({
    variables,
    participantAssignments: participantAssignments.value,
  })
}

function currentVariables() {
  return mergeInteractionVariables(
    buildProcessVariables(variableRows.value),
    buildInteractionVariables(interactionFields.value, interactionValues.value),
  )
}

function submit() {
  if (requirementsLoaded.value) continueStart()
  else requestRequirements(true)
}

function variablePlaceholder(type: ProcessVariableType) {
  return {
    string: 'Nhập văn bản',
    number: 'ví dụ 100',
    boolean: 'Chọn có hoặc không',
    json: 'ví dụ {"key":"value"}',
  }[type]
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="Bắt đầu quy trình"
    width="min(800px, calc(100vw - 32px))"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="resetForm"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="Quy trình" required>
        <el-select
          v-model="form.processDefinitionId"
          filterable
          :loading="definitionsQuery.isFetching.value"
          :disabled="definitionsQuery.isFetching.value && !activeDefinitions.length"
          loading-text="Đang tải quy trình"
          no-data-text="Chưa có quy trình nào để bắt đầu"
          placeholder="Chọn quy trình"
        >
          <el-option
            v-for="definition in activeDefinitions"
            :key="definition.processDefinitionId"
            :label="`${definition.processDefinitionName} / ${definition.processDefinitionKey} · ${formatVersion(definition.version)}`"
            :value="definition.processDefinitionId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="Mã hồ sơ">
        <el-input
          v-model="form.businessKey"
          maxlength="128"
          placeholder="Không bắt buộc. Ví dụ LEAVE-2026-0001"
        />
      </el-form-item>

      <InteractionDataFields
        v-model="interactionValues"
        :fields="interactionFields"
        :agent-activity-ids="interactionAgentActivityIds"
        :loading="interactionLoading"
      />

      <section class="variable-section">
        <div class="variable-section__heading">
          <div>
            <h3>Thêm dữ liệu</h3>
            <p>Không bắt buộc. Quy trình có thể dùng các giá trị này.</p>
          </div>
          <el-button link type="primary" @click="addVariable"
            ><Plus :size="15" />Thêm dữ liệu</el-button
          >
        </div>
        <div
          v-if="variableRows.length"
          class="variable-grid variable-grid--header"
          aria-hidden="true"
        >
          <span>Tên</span><span>Loại</span><span>Giá trị</span><span />
        </div>
        <div v-for="(row, index) in variableRows" :key="index" class="variable-grid">
          <el-input v-model="row.name" placeholder="ví dụ amount" />
          <el-select v-model="row.type">
            <el-option label="Văn bản" value="string" />
            <el-option label="Số" value="number" />
            <el-option label="Có hoặc không" value="boolean" />
            <el-option label="JSON" value="json" />
          </el-select>
          <el-select v-if="row.type === 'boolean'" v-model="row.value" placeholder="Hãy chọn">
            <el-option label="Có" value="true" />
            <el-option label="Không" value="false" />
          </el-select>
          <el-input v-else v-model="row.value" :placeholder="variablePlaceholder(row.type)" />
          <el-button
            class="variable-remove"
            text
            type="danger"
            aria-label="Xóa dòng"
            @click="removeVariable(index)"
          >
            <Trash2 :size="15" />
          </el-button>
        </div>
        <div v-if="!variableRows.length" class="variable-empty">Chưa thêm dữ liệu</div>
      </section>
      <div
        v-if="selectedDefinition && requirementsMutation.isPending.value && !requirementsLoaded"
        class="participant-status participant-status--loading"
      >
        Đang xem bước nào cần chọn người…
      </div>
      <el-alert
        v-else-if="selectedDefinition && requirementsLoaded && !participantRequirements.length"
        class="participant-status"
        title="Không cần chọn người"
        description="Hệ thống sẽ tự giao. Nếu quy trình đi qua bước tự động trước, người nhận được chọn khi chạy."
        type="success"
        show-icon
        :closable="false"
      />
      <ParticipantAssignmentEditor
        v-model="participantAssignments"
        :requirements="participantRequirements"
      />
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">Hủy</el-button>
      <el-button
        type="primary"
        :disabled="!selectedDefinition"
        :loading="
          startMutation.isPending.value ||
          requirementsMutation.isPending.value ||
          interactionLoading
        "
        @click="submit"
      >
        Bắt đầu
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.variable-section {
  display: grid;
  gap: 10px;
  padding-top: 4px;
}

.variable-section__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.variable-section__heading h3,
.variable-section__heading p {
  margin: 0;
}

.variable-section__heading h3 {
  color: var(--color-text);
  font-size: 14px;
}

.variable-section__heading p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.variable-grid {
  display: grid;
  grid-template-columns: minmax(130px, 0.8fr) 112px minmax(170px, 1.2fr) 36px;
  align-items: center;
  gap: 8px;
}

.variable-grid--header {
  color: var(--color-text-muted);
  font-size: 12px;
}

.variable-remove {
  width: 36px;
  margin: 0;
}

.variable-empty {
  padding: 18px 0;
  border: 1px dashed var(--color-border);
  border-radius: 10px;
  color: var(--color-text-subtle);
  font-size: 12px;
  text-align: center;
}

.participant-status {
  margin-top: 14px;
}

.participant-status--loading {
  padding: 13px 15px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-primary-soft);
  color: var(--color-text-muted);
  font-size: 13px;
}

@media (max-width: 620px) {
  .variable-grid {
    grid-template-columns: minmax(0, 1fr) 112px 36px;
  }

  .variable-grid > :nth-child(3) {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .variable-grid--header {
    display: none;
  }
}
</style>
