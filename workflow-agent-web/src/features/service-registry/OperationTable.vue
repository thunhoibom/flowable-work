<script setup lang="ts">
import type { CatalogOperation } from './api'
import { operationPublishStatus, operationStatusLabel } from './catalogStatus'

defineProps<{
  operations: CatalogOperation[]
  selectedOperationKey: string
}>()

const emit = defineEmits<{
  select: [operationKey: string]
  add: []
}>()
</script>

<template>
  <div class="operation-table">
    <div class="operation-table__header">
      <strong>Thao tác</strong>
      <el-button data-action="add-operation" @click="emit('add')">Thêm thao tác</el-button>
    </div>
    <p v-if="!operations.length">Chưa có thao tác</p>
    <table v-else>
      <thead>
        <tr>
          <th>Phương thức</th>
          <th>Mã thao tác</th>
          <th>Mẫu địa chỉ</th>
          <th>Trạng thái</th>
          <th>Khóa</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="operation in operations"
          :key="operation.operationKey"
          :data-status="operationPublishStatus(operation)"
          :aria-selected="operation.operationKey === selectedOperationKey"
        >
          <td>{{ operation.method }}</td>
          <td>
            <button
              type="button"
              :data-operation="operation.operationKey"
              @click="emit('select', operation.operationKey)"
            >
              {{ operation.operationKey }}
            </button>
          </td>
          <td>{{ operation.urlTemplate }}</td>
          <td>{{ operationStatusLabel[operationPublishStatus(operation)] }}</td>
          <td>{{ operation.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.operation-table {
  display: grid;
  gap: 12px;
}

.operation-table__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.operation-table p {
  margin: 0;
  color: var(--color-text-muted);
}

.operation-table table {
  width: 100%;
  border-collapse: collapse;
}

.operation-table th,
.operation-table td {
  padding: 10px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  text-align: left;
  font-size: 13px;
}

.operation-table tr[aria-selected='true'] {
  background: var(--color-primary-soft);
}

.operation-table button {
  padding: 0;
  border: 0;
  color: var(--color-primary-strong);
  background: transparent;
  font: inherit;
  cursor: pointer;
}
</style>
