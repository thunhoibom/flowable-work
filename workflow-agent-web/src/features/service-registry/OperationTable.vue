<script setup lang="ts">
import { Plus, SquarePen, Trash2 } from '@lucide/vue'
import TableTagCell from '@/components/TableTagCell.vue'
import type { CatalogOperation } from './api'
import {
  operationPublishStatus,
  operationStatusLabel,
  type OperationPublishStatus,
} from './catalogStatus'

withDefaults(
  defineProps<{
    operations: CatalogOperation[]
    loading?: boolean
  }>(),
  { loading: false },
)

const emit = defineEmits<{
  select: [operationKey: string]
  add: []
  remove: [operationKey: string]
}>()

const statusTag: Record<OperationPublishStatus, 'success' | 'warning' | 'info'> = {
  published: 'success',
  changed: 'warning',
  unpublished: 'info',
}
</script>

<template>
  <div class="operation-table">
    <div class="operation-table__header">
      <strong>Thao tác</strong>
      <el-button data-action="add-operation" @click="emit('add')">
        <Plus :size="16" />Thêm thao tác
      </el-button>
    </div>
    <el-table v-loading="loading" :data="operations" row-key="operationKey" table-layout="fixed">
      <el-table-column label="STT" width="64" align="center">
        <template #default="{ $index }">
          <span class="operation-table__index">{{ $index + 1 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Phương thức" width="110">
        <template #default="{ row }">
          <code class="operation-table__method">{{ row.method }}</code>
        </template>
      </el-table-column>
      <el-table-column label="Mã thao tác" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.operationKey }}</template>
      </el-table-column>
      <el-table-column label="Mẫu địa chỉ" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ row.urlTemplate }}</template>
      </el-table-column>
      <el-table-column label="Nội dung" width="100">
        <!-- Operations saved before content types existed are JSON. -->
        <template #default="{ row }">{{ row.contentType || 'JSON' }}</template>
      </el-table-column>
      <el-table-column label="Trạng thái" width="140">
        <template #default="{ row }">
          <TableTagCell>
            <el-tag
              :type="statusTag[operationPublishStatus(row)]"
              effect="light"
              round
              :data-status="operationPublishStatus(row)"
            >
              {{ operationStatusLabel[operationPublishStatus(row)] }}
            </el-tag>
          </TableTagCell>
        </template>
      </el-table-column>
      <el-table-column label="Khóa" width="130">
        <template #default="{ row }">{{ row.secretPresent ? 'Đã lưu khóa' : 'Không có khóa' }}</template>
      </el-table-column>
      <el-table-column label="Hành động" width="150" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button
              link
              type="primary"
              data-action="edit-operation"
              :data-operation="row.operationKey"
              @click="emit('select', row.operationKey)"
            >
              <SquarePen :size="14" />Sửa
            </el-button>
            <el-button
              link
              type="danger"
              data-action="delete-operation"
              :data-operation-key="row.operationKey"
              @click="emit('remove', row.operationKey)"
            >
              <Trash2 :size="14" />Xóa
            </el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <p class="operation-table__empty">Chưa có thao tác</p>
      </template>
    </el-table>
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
  font-size: 16px;
}

.operation-table__index {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.operation-table__method {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 13px;
}

.operation-table__empty {
  margin: 0;
  color: var(--color-text-muted);
}
</style>
