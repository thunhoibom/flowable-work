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
  remove: [operationKey: string]
}>()
</script>

<template>
  <div class="operation-table">
    <div class="operation-table__header">
      <strong>Thao tác</strong>
      <el-button data-action="add-operation" @click="emit('add')">Thêm thao tác</el-button>
    </div>
    <p v-if="!operations.length">Chưa có thao tác</p>
    <div v-else class="operation-table__rows">
      <div class="operation-table__head" aria-hidden="true">
        <span>Phương thức</span>
        <span>Mã thao tác</span>
        <span>Mẫu địa chỉ</span>
        <span>Trạng thái</span>
        <span>Khóa</span>
      </div>
      <div v-for="operation in operations" :key="operation.operationKey" class="operation-line">
        <button
          type="button"
          class="operation-row"
          :data-operation="operation.operationKey"
          :data-status="operationPublishStatus(operation)"
          :aria-current="operation.operationKey === selectedOperationKey ? 'true' : undefined"
          @click="emit('select', operation.operationKey)"
        >
          <span>{{ operation.method }}</span>
          <span>{{ operation.operationKey }}</span>
          <span class="operation-row__url">{{ operation.urlTemplate }}</span>
          <span>{{ operationStatusLabel[operationPublishStatus(operation)] }}</span>
          <span>{{ operation.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}</span>
        </button>
        <el-button
          data-action="delete-operation"
          :data-operation-key="operation.operationKey"
          @click="emit('remove', operation.operationKey)"
        >
          Xóa
        </el-button>
      </div>
    </div>
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

.operation-table__header strong {
  font-size: 18px;
}

.operation-table p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 15px;
}

.operation-table__rows {
  display: grid;
  gap: 8px;
}

.operation-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: stretch;
}

.operation-table__head,
.operation-row {
  display: grid;
  grid-template-columns: 96px minmax(120px, 0.8fr) minmax(160px, 1.6fr) 148px 124px;
  gap: 12px;
  align-items: center;
}

.operation-table__head {
  padding: 0 16px;
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 650;
}

.operation-row {
  width: 100%;
  min-height: 56px;
  padding: 14px 16px;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-md);
  color: var(--color-text);
  background: var(--color-surface);
  font-size: 15px;
  line-height: 1.4;
  text-align: left;
  cursor: pointer;
}

.operation-row:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.operation-row[aria-current='true'] {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.operation-row__url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 959px) {
  .operation-table__head {
    display: none;
  }

  .operation-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .operation-row__url {
    white-space: normal;
  }
}
</style>
