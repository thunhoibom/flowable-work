<script setup lang="ts">
import { computed, ref } from 'vue'
import { UserRoundSearch } from '@lucide/vue'
import type { ParticipantAssignment, ParticipantRequirement } from '../types'
import UserPickerDialog from './UserPickerDialog.vue'

const props = defineProps<{
  requirements: ParticipantRequirement[]
  modelValue: ParticipantAssignment[]
}>()
const emit = defineEmits<{
  'update:modelValue': [value: ParticipantAssignment[]]
}>()

const pickerVisible = ref(false)
const activeActivityId = ref('')
const activeRequirement = computed(() =>
  props.requirements.find((item) => item.activityId === activeActivityId.value),
)

function assignment(activityId: string) {
  return props.modelValue.find((item) => item.activityId === activityId)
}

function selectedUsers(activityId: string) {
  return assignment(activityId)?.usernames ?? []
}

function openPicker(requirement: ParticipantRequirement) {
  activeActivityId.value = requirement.activityId
  pickerVisible.value = true
}

function saveSelection(usernames: string[]) {
  const next = props.modelValue.filter((item) => item.activityId !== activeActivityId.value)
  if (usernames.length) next.push({ activityId: activeActivityId.value, usernames })
  emit('update:modelValue', next)
}

function removeUser(activityId: string, username: string) {
  saveForActivity(
    activityId,
    selectedUsers(activityId).filter((item) => item !== username),
  )
}

function saveForActivity(activityId: string, usernames: string[]) {
  const next = props.modelValue.filter((item) => item.activityId !== activityId)
  if (usernames.length) next.push({ activityId, usernames })
  emit('update:modelValue', next)
}

function assignmentLabel(requirement: ParticipantRequirement) {
  return {
    ASSIGNEE: 'Một người',
    CANDIDATE_USERS: 'Có thể nhận',
    CANDIDATE_GROUPS: 'Nhóm',
    COUNTERSIGN_USERS: 'Cùng xử lý',
    MIXED: 'Có thể nhận',
  }[requirement.assignmentType]
}
</script>

<template>
  <section v-if="requirements.length" class="participant-section">
    <div class="participant-section__heading">
      <div>
        <h3>Giao việc</h3>
        <p>Giao từng bước cho một người. Bước không bắt buộc có thể để trống, hệ thống sẽ tự giao.</p>
      </div>
    </div>
    <article
      v-for="requirement in requirements"
      :key="requirement.activityId"
      class="participant-card"
    >
      <div class="participant-card__title">
        <div>
          <strong>{{ requirement.activityName }}</strong>
          <small>{{ requirement.activityId }}</small>
        </div>
        <div class="participant-card__badges">
          <el-tag size="small" effect="plain">{{ assignmentLabel(requirement) }}</el-tag>
          <el-tag size="small" :type="requirement.required ? 'warning' : 'success'" effect="light">
            {{ requirement.required ? 'Bắt buộc' : 'Không bắt buộc' }}
          </el-tag>
        </div>
      </div>
      <div class="participant-card__selection">
        <div v-if="selectedUsers(requirement.activityId).length" class="participant-tags">
          <el-tag
            v-for="username in selectedUsers(requirement.activityId)"
            :key="username"
            closable
            @close="removeUser(requirement.activityId, username)"
          >
            {{ username }}
          </el-tag>
        </div>
        <span v-else class="participant-placeholder">
          {{ requirement.required ? 'Chưa chọn người' : 'Chưa chọn. Hệ thống sẽ tự giao.' }}
        </span>
        <el-button type="primary" plain @click="openPicker(requirement)">
          <UserRoundSearch :size="15" />
          {{ selectedUsers(requirement.activityId).length ? 'Chọn lại' : 'Chọn người' }}
        </el-button>
      </div>
    </article>
  </section>

  <UserPickerDialog
    v-if="activeRequirement"
    v-model="pickerVisible"
    :title="`Chọn người cho “${activeRequirement.activityName}”`"
    :multiple="activeRequirement.multiple"
    :selected="selectedUsers(activeRequirement.activityId)"
    @confirm="saveSelection"
  />
</template>

<style scoped>
.participant-section {
  display: grid;
  gap: 10px;
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--color-border);
}

.participant-section__heading h3,
.participant-section__heading p {
  margin: 0;
}

.participant-section__heading h3 {
  color: var(--color-text);
  font-size: 14px;
}

.participant-section__heading p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.participant-card {
  padding: 12px;
  border: 1px solid var(--color-border-soft);
  border-radius: 12px;
  background: var(--color-primary-soft);
}

.participant-card__title,
.participant-card__selection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.participant-card__title > div:first-child {
  display: grid;
}

.participant-card__badges {
  display: flex;
  align-items: center;
  gap: 6px;
}

.participant-card__title small {
  margin-top: 2px;
  color: var(--color-text-muted);
}

.participant-card__selection {
  margin-top: 10px;
}

.participant-tags {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
}

.participant-placeholder {
  color: var(--color-text-subtle);
  font-size: 13px;
}

@media (max-width: 520px) {
  .participant-card__selection {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
