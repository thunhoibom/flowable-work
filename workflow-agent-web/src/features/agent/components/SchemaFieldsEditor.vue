<script setup lang="ts">
import { Plus } from '@lucide/vue'
import type { SchemaField } from '../composables/useAgentSchemaEditor'

const props = defineProps<{
  fields: SchemaField[]
  readonly?: boolean
  direction: 'input' | 'output'
}>()

const emit = defineEmits<{
  add: []
  remove: [index: number]
}>()
</script>

<template>
  <div class="schema-editor">
    <div class="schema-editor__toolbar">
      <span>{{
        direction === 'input'
          ? 'Khai báo dữ liệu Agent cần nhận.'
          : 'Khai báo dữ liệu Agent sẽ trả về.'
      }}</span>
      <el-button link type="primary" :disabled="readonly" @click="emit('add')">
        <Plus :size="15" />Thêm trường
      </el-button>
    </div>
    <el-table :data="props.fields" size="small" border>
      <el-table-column label="Đường dẫn trường" min-width="180">
        <template #default="{ row }">
          <el-input
            v-model="row.path"
            :placeholder="direction === 'input' ? 'ví dụ customer.name' : 'ví dụ result.summary'"
            :disabled="readonly"
          />
        </template>
      </el-table-column>
      <el-table-column label="Loại" width="130">
        <template #default="{ row }">
          <el-select v-model="row.type" :disabled="readonly">
            <el-option label="Văn bản" value="string" />
            <el-option label="Số" value="number" />
            <el-option label="Số nguyên" value="integer" />
            <el-option label="Giá trị đúng/sai" value="boolean" />
            <el-option label="Đối tượng" value="object" />
            <el-option label="Mảng" value="array" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="Phần tử mảng" width="130">
        <template #default="{ row }">
          <el-select v-model="row.itemType" :disabled="readonly || row.type !== 'array'">
            <el-option label="Văn bản" value="string" />
            <el-option label="Số" value="number" />
            <el-option label="Số nguyên" value="integer" />
            <el-option label="Giá trị đúng/sai" value="boolean" />
            <el-option label="Đối tượng" value="object" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="Bắt buộc" width="75" align="center">
        <template #default="{ row }"
          ><el-checkbox v-model="row.required" :disabled="readonly"
        /></template>
      </el-table-column>
      <el-table-column label="Mô tả" min-width="150">
        <template #default="{ row }"
          ><el-input v-model="row.description" :disabled="readonly"
        /></template>
      </el-table-column>
      <el-table-column label="Thao tác" width="70" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :disabled="readonly" @click="emit('remove', $index)"
            >Xóa</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!fields.length" class="schema-editor__empty">
      {{
        direction === 'input'
          ? 'Chưa có dữ liệu đầu vào. Agent nhận dữ liệu trống, hoặc dữ liệu quy trình gửi sang.'
          : 'Chưa có dữ liệu đầu ra. Agent có thể trả về nội dung tự do.'
      }}
    </div>
  </div>
</template>

<style scoped>
.schema-editor {
  width: 100%;
  padding: 10px;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-sm);
  background: var(--color-surface-muted);
}

.schema-editor__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.schema-editor__empty {
  padding: 16px 10px 6px;
  color: var(--color-text-subtle);
  text-align: center;
  font-size: 12px;
}
</style>
