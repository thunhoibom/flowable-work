<script setup lang="ts">
import { ref } from 'vue'
import { UserRoundSearch } from '@lucide/vue'
import { UserPickerDialog } from '@/features/process-instance'

const props = defineProps<{
  modelValue: string[]
  title: string
  multiple: boolean
  emptyText: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const pickerVisible = ref(false)

function remove(username: string) {
  emit(
    'update:modelValue',
    props.modelValue.filter((item) => item !== username),
  )
}
</script>

<template>
  <div class="rule-user-picker-field">
    <div v-if="modelValue.length" class="rule-user-picker-field__tags">
      <el-tag v-for="username in modelValue" :key="username" closable @close="remove(username)">
        {{ username }}
      </el-tag>
    </div>
    <span v-else class="rule-user-picker-field__placeholder">{{ emptyText }}</span>
    <el-button type="primary" plain @click="pickerVisible = true">
      <UserRoundSearch :size="15" />
      {{ modelValue.length ? 'Chọn lại' : 'Tìm và chọn' }}
    </el-button>
  </div>

  <UserPickerDialog
    v-model="pickerVisible"
    :title="title"
    :multiple="multiple"
    :selected="modelValue"
    @confirm="emit('update:modelValue', $event)"
  />
</template>

<style scoped>
.rule-user-picker-field {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  min-height: var(--control-height);
  width: 100%;
  gap: 10px;
  padding: 6px 8px 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--control-radius);
  background: var(--color-surface);
}

.rule-user-picker-field__tags {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
}

.rule-user-picker-field__placeholder {
  flex: 1;
  color: var(--color-text-subtle);
  font-size: 13px;
}

.rule-user-picker-field :deep(.el-button) {
  flex: 0 0 auto;
}

@media (max-width: 520px) {
  .rule-user-picker-field {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
