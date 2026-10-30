<script setup lang="ts">
import StatusBadge from '@/components/StatusBadge.vue'
import ListEmptyState from '@/components/ListEmptyState.vue'
import type { AgentProvider, AgentProviderType } from '../types'

defineProps<{
  rows: AgentProvider[]
  loading: boolean
  providerTypeLabel: (type: AgentProviderType) => string
}>()

const emit = defineEmits<{
  edit: [provider: AgentProvider]
}>()
</script>

<template>
  <el-table class="provider-table" v-loading="loading" :data="rows" height="100%">
    <el-table-column prop="name" label="Tên nhà cung cấp" min-width="180" />
    <el-table-column prop="code" label="Mã" min-width="150" />
    <el-table-column label="Loại" width="140">
      <template #default="{ row }">{{ providerTypeLabel(row.type) }}</template>
    </el-table-column>
    <el-table-column prop="baseUrl" label="Địa chỉ API" min-width="230" show-overflow-tooltip>
      <template #default="{ row }">{{ row.baseUrl || 'Mock cục bộ' }}</template>
    </el-table-column>
    <el-table-column prop="defaultModel" label="Mô hình mặc định" min-width="150">
      <template #default="{ row }">{{ row.defaultModel || '—' }}</template>
    </el-table-column>
    <el-table-column label="Trạng thái thông tin xác thực" width="110" align="center" header-align="center">
      <template #default="{ row }">
        <StatusBadge
          :status="
            row.credentialConfigured ? 'SUCCESS' : row.type === 'MOCK' ? 'SKIPPED' : 'PENDING'
          "
          :tone="row.credentialConfigured ? 'success' : row.type === 'MOCK' ? 'info' : 'warning'"
          :label="row.credentialConfigured ? 'Đã cấu hình' : row.type === 'MOCK' ? 'Không cần thông tin xác thực' : 'Chưa cấu hình'"
        />
      </template>
    </el-table-column>
    <el-table-column label="Bốn số cuối của khóa" width="110" align="center" header-align="center">
      <template #default="{ row }">
        <code v-if="row.credentialConfigured && row.credentialHint" class="credential-hint">
          •••• {{ row.credentialHint }}
        </code>
        <span v-else class="credential-empty">—</span>
      </template>
    </el-table-column>
    <el-table-column
      class-name="agent-status-column"
      label="Trạng thái"
      width="110"
      align="center"
      header-align="center"
    >
      <template #default="{ row }"><StatusBadge :status="row.enabled" /></template>
    </el-table-column>
    <el-table-column label="Thao tác" width="90" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="emit('edit', row)">Sửa</el-button>
      </template>
    </el-table-column>
    <template #empty>
      <ListEmptyState
        title="Chưa cấu hình nhà cung cấp"
        description="Thêm nhà cung cấp và khóa trước, rồi tạo phiên bản Agent để phát hành."
      />
    </template>
  </el-table>
</template>
