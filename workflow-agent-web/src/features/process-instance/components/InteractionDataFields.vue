<script setup lang="ts">
import type { InteractionDataField } from '../types'
import type { InteractionValues } from '../interaction'

defineProps<{
  fields: InteractionDataField[]
  agentActivityIds?: string[]
  loading?: boolean
}>()
const values = defineModel<InteractionValues>({ required: true })

function placeholder(field: InteractionDataField) {
  if (field.dataType === 'object') return 'Ví dụ {"name":"ví dụ"}'
  if (field.dataType === 'array') return 'Ví dụ ["A","B"]'
  return field.description || `Hãy nhập ${field.label}`
}
</script>

<template>
  <section
    v-if="fields.length || (!loading && agentActivityIds?.length)"
    class="interaction-fields"
    v-loading="loading"
  >
    <header>
      <div>
        <h3>Thông tin cho Agent</h3>
        <p>Đây là thông tin bước Agent sắp tới cần. Sau khi gửi, hệ thống tự điền vào đúng chỗ.</p>
      </div>
    </header>
    <div class="interaction-fields__grid">
      <el-form-item
        v-for="field in fields"
        :key="`${field.agentActivityId}:${field.variablePath}`"
        :label="field.label"
        :required="field.required"
        class="interaction-field"
      >
        <el-select
          v-if="field.dataType === 'boolean'"
          v-model="values[field.variablePath]"
          clearable
          placeholder="Hãy chọn"
        >
          <el-option label="Có" :value="true" />
          <el-option label="Không" :value="false" />
        </el-select>
        <el-input-number
          v-else-if="field.dataType === 'integer' || field.dataType === 'number'"
          v-model="values[field.variablePath] as number"
          :precision="field.dataType === 'integer' ? 0 : undefined"
          :controls="false"
          class="interaction-field__number"
          :placeholder="placeholder(field)"
        />
        <el-input
          v-else-if="field.dataType === 'object' || field.dataType === 'array'"
          v-model="values[field.variablePath] as string"
          type="textarea"
          :rows="3"
          :placeholder="placeholder(field)"
        />
        <el-input
          v-else
          v-model="values[field.variablePath] as string"
          :type="field.format === 'multiline' ? 'textarea' : 'text'"
          :rows="field.format === 'multiline' ? 3 : undefined"
          :placeholder="placeholder(field)"
        />
        <p v-if="field.description" class="interaction-field__description">
          {{ field.description }}
        </p>
        <span class="interaction-field__source">Dùng cho: {{ field.agentActivityName }}</span>
      </el-form-item>
    </div>
    <div v-if="!fields.length" class="interaction-fields__empty">
      Bước Agent tiếp theo không cần bạn điền thêm. Công việc sẽ chạy theo quy trình đã phát hành.
    </div>
  </section>
</template>

<style scoped>
.interaction-fields {
  margin: 14px 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-primary-soft);
}

.interaction-fields header h3,
.interaction-fields header p,
.interaction-field__description {
  margin: 0;
}

.interaction-fields header h3 {
  color: var(--color-text);
  font-size: 14px;
}

.interaction-fields header p,
.interaction-field__description,
.interaction-field__source {
  color: var(--color-text-muted);
  font-size: 12px;
}

.interaction-fields header p {
  margin-top: 4px;
}

.interaction-fields__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
  margin-top: 14px;
}

.interaction-fields__empty {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px dashed var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: 12px;
}

.interaction-field__number {
  width: 100%;
}

.interaction-field__description {
  width: 100%;
  margin-top: 5px;
}

.interaction-field__source {
  display: block;
  width: 100%;
  margin-top: 4px;
}

@media (max-width: 620px) {
  .interaction-fields__grid {
    grid-template-columns: 1fr;
  }
}
</style>
