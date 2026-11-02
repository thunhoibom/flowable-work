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
        <span>STT</span>
        <span>Phương thức</span>
        <span>Mã thao tác</span>
        <span>Mẫu địa chỉ</span>
        <span>Nội dung</span>
        <span>Trạng thái</span>
        <span>Khóa</span>
        <span>Thao tác</span>
      </div>
      <div
        v-for="(operation, index) in operations"
        :key="operation.operationKey"
        class="operation-row"
        :data-status="operationPublishStatus(operation)"
        :aria-current="operation.operationKey === selectedOperationKey ? 'true' : undefined"
      >
        <span class="operation-row__index">{{ index + 1 }}</span>
        <span>{{ operation.method }}</span>
        <span>{{ operation.operationKey }}</span>
        <span class="operation-row__url">{{ operation.urlTemplate }}</span>
        <!-- Operations saved before content types existed are JSON. -->
        <span>{{ operation.contentType || 'JSON' }}</span>
        <span>{{ operationStatusLabel[operationPublishStatus(operation)] }}</span>
        <span>{{ operation.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}</span>
        <span class="operation-row__actions">
          <el-button
            link
            type="primary"
            data-action="edit-operation"
            :data-operation="operation.operationKey"
            @click="emit('select', operation.operationKey)"
          >
            Sửa
          </el-button>
          <el-button
            link
            type="danger"
            data-action="delete-operation"
            :data-operation-key="operation.operationKey"
            @click="emit('remove', operation.operationKey)"
          >
            Xóa
          </el-button>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.operation-table {
  display: grid;
  gap: 12px;
  min-width: 0;
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
  grid-template-columns:
    40px
    minmax(0, max-content)
    minmax(0, 0.9fr)
    minmax(0, 1.6fr)
    minmax(0, max-content)
    minmax(0, max-content)
    minmax(0, max-content)
    max-content;
  column-gap: 12px;
  align-items: center;
  min-width: 0;
}

.operation-table__head,
.operation-row {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: subgrid;
  align-items: center;
  min-width: 0;
}

.operation-table__head > span,
.operation-row > span:not(.operation-row__actions) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.operation-table__head {
  min-height: 32px;
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 650;
}

.operation-row {
  min-height: 56px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border-soft);
  color: var(--color-text);
  font-size: 15px;
  line-height: 1.4;
  text-align: left;
}

.operation-row__index {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.operation-row__actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.operation-row[aria-current='true'] {
  background: var(--color-primary-soft);
}

.operation-row__url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 959px) {
  .operation-table__rows {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 12px;
  }

  .operation-table__head {
    display: none;
  }

  .operation-row {
    grid-column: auto;
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
    padding: 8px 0;
  }

  .operation-row__url {
    white-space: normal;
  }
}
</style>
