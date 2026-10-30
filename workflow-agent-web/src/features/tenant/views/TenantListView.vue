<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw, Search } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TablePagination from '@/components/TablePagination.vue'
import MetricCard from '@/components/MetricCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime } from '@/utils/format'
import { tenantApi } from '../api'
import type { TenantCommand, WorkflowTenant } from '../types'

const queryClient = useQueryClient()
const query = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
  pageNum: 1,
  pageSize: 20,
})
const applied = ref({ ...query })
const dialogVisible = ref(false)
const editingId = ref<number>()
const form = reactive<TenantCommand>({
  tenantId: '',
  tenantCode: '',
  tenantName: '',
  description: '',
  enabled: true,
})
const tenantsQuery = useQuery({
  queryKey: computed(() => queryKeys.tenantPage(applied.value)),
  queryFn: () => tenantApi.page(applied.value),
})
const records = computed(() => tenantsQuery.data.value?.records ?? [])
const total = computed(() => tenantsQuery.data.value?.total ?? 0)
const enabledCount = computed(() => records.value.filter((item) => item.enabled).length)
const disabledCount = computed(() => Math.max(records.value.length - enabledCount.value, 0))
const saveMutation = useMutation({
  mutationFn: async () => {
    if (!form.tenantId.trim() || !form.tenantCode.trim() || !form.tenantName.trim())
      throw new Error('Hãy nhập mã, mã ngắn và tên tổ chức')
    const payload = {
      ...form,
      tenantId: form.tenantId.trim(),
      tenantCode: form.tenantCode.trim(),
      tenantName: form.tenantName.trim(),
      description: form.description?.trim(),
    }
    if (editingId.value) await tenantApi.update(editingId.value, payload)
    else await tenantApi.create(payload)
  },
  onSuccess: async () => {
    dialogVisible.value = false
    ElMessage.success(editingId.value ? 'Đã cập nhật tổ chức' : 'Đã tạo tổ chức')
    await queryClient.invalidateQueries({ queryKey: queryKeys.tenants() })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})
function search() {
  query.pageNum = 1
  applied.value = { ...query }
}
function reset() {
  Object.assign(query, { keyword: '', enabled: undefined, pageNum: 1 })
  applied.value = { ...query }
}
function changePage(pageNum: number, pageSize: number) {
  query.pageNum = pageNum
  query.pageSize = pageSize
  applied.value = { ...query }
}
function create() {
  editingId.value = undefined
  Object.assign(form, {
    tenantId: '',
    tenantCode: '',
    tenantName: '',
    description: '',
    enabled: true,
  })
  dialogVisible.value = true
}
function edit(item: WorkflowTenant) {
  editingId.value = item.id
  Object.assign(form, {
    tenantId: item.tenantId,
    tenantCode: item.tenantCode,
    tenantName: item.tenantName,
    description: item.description || '',
    enabled: item.enabled,
  })
  dialogVisible.value = true
}
async function toggle(item: WorkflowTenant) {
  const next = !item.enabled
  const confirmed = await confirmAction(
    next
      ? `Bật “${item.tenantName}” để mọi người trong tổ chức này vào làm việc?`
      : `Tắt “${item.tenantName}” sẽ chặn vào quy trình của tổ chức này. Tiếp tục?`,
    `${next ? 'Bật' : 'Tắt'} tổ chức`,
    { confirmButtonText: next ? 'Bật' : 'Tắt', cancelButtonText: 'Hủy', type: 'warning' },
  )
  if (!confirmed) return
  try {
    await tenantApi.updateEnabled(item.id, next)
    ElMessage.success(next ? 'Đã bật tổ chức' : 'Đã tắt tổ chức')
    await queryClient.invalidateQueries({ queryKey: queryKeys.tenants() })
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Không gian làm việc"
      title="Tổ chức"
      description="Mỗi tổ chức có quy trình, công việc và thành viên riêng."
    />

    <section class="metric-grid">
      <MetricCard :value="total" label="Tổ chức"><template #icon>Σ</template></MetricCard>
      <MetricCard :value="enabledCount" label="Đang dùng" tone="success">
        <template #icon>✓</template>
      </MetricCard>
      <MetricCard :value="disabledCount" label="Đã tắt" tone="warning">
        <template #icon>–</template>
      </MetricCard>
    </section>

    <section class="page-actions compact-filter">
      <el-form class="filter-form filter-form--tenants" inline @submit.prevent="search"
        ><el-form-item label="Từ khóa"
          ><el-input
            v-model="query.keyword"
            clearable
            placeholder="Tên hoặc mã tổ chức" /></el-form-item
        ><el-form-item label="Trạng thái"
          ><el-select v-model="query.enabled" clearable
            ><el-option label="Bật" :value="true" /><el-option
              label="Tắt"
              :value="false" /></el-select></el-form-item
        ><el-form-item class="filter-form__actions"
          ><el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button
          ><el-button @click="reset"><RefreshCw :size="16" />Đặt lại</el-button></el-form-item
        ></el-form
      >
      <div class="action-buttons page-primary-actions">
        <el-button type="primary" @click="create"><Plus :size="17" />Thêm tổ chức</el-button>
      </div>
    </section>
    <section class="table-panel">
      <el-table v-loading="tenantsQuery.isFetching.value" :data="records" height="100%"
        ><el-table-column prop="tenantName" label="Tên tổ chức" min-width="180" /><el-table-column
          prop="tenantId"
          label="Mã tổ chức"
          min-width="180" /><el-table-column
          prop="tenantCode"
          label="Mã ngắn"
          min-width="150" /><el-table-column
          prop="description"
          label="Mô tả"
          min-width="220"
          show-overflow-tooltip /><el-table-column
          label="Trạng thái"
          width="110"
          align="center"
          header-align="center"
          ><template #default="{ row }"
            ><StatusBadge :status="row.enabled" /></template></el-table-column
        ><el-table-column label="Thời điểm cập nhật" width="175"
          ><template #default="{ row }">{{
            formatDateTime(row.updatedAt)
          }}</template></el-table-column
        ><el-table-column label="Thao tác" width="150" fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)">Sửa</el-button
            ><el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggle(row)">{{
              row.enabled ? 'Tắt' : 'Bật'
            }}</el-button></template
          ></el-table-column
        ><template #empty>
          <ListEmptyState
            title="Chưa có tổ chức"
            description="Tạo tổ chức trước, rồi thêm thành viên và quy trình."
          /> </template></el-table
      ><TablePagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        aria-label="Phân trang tổ chức"
        @change="changePage"
      />
    </section>
    <el-dialog v-model="dialogVisible" :title="editingId ? 'Sửa tổ chức' : 'Thêm tổ chức'" width="560px"
      ><el-form class="dialog-form" label-position="top"
        ><el-form-item label="Mã tổ chức" required
          ><el-input
            v-model="form.tenantId"
            :disabled="Boolean(editingId)"
            maxlength="64" /></el-form-item
        ><el-form-item label="Mã ngắn" required
          ><el-input v-model="form.tenantCode" maxlength="64" /></el-form-item
        ><el-form-item label="Tên tổ chức" required
          ><el-input v-model="form.tenantName" maxlength="128" /></el-form-item
        ><el-form-item label="Mô tả"
          ><el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit /></el-form-item
        ><el-form-item label="Bật"><el-switch v-model="form.enabled" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialogVisible = false">Hủy</el-button
        ><el-button
          type="primary"
          :loading="saveMutation.isPending.value"
          @click="saveMutation.mutate()"
          >Lưu</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
