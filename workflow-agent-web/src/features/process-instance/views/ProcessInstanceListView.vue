<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Ban, Eye, Play, RefreshCw, Search } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TablePagination from '@/components/TablePagination.vue'
import MetricCard from '@/components/MetricCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { canOperateInstances as isInstanceOperable } from '@/features/auth/authorization'
import { useAuthStore } from '@/stores/auth'
import { promptRequired } from '@/utils/confirmation'
import { formatDateTime, formatDuration } from '@/utils/format'
import { processInstanceApi } from '../api'
import StartProcessDialog from '../components/StartProcessDialog.vue'
import type { StartProcessResult } from '../types'

const router = useRouter()
const queryClient = useQueryClient()
const authStore = useAuthStore()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canOperateInstances = computed(() => isInstanceOperable(authStore.user))
const query = reactive({
  processDefinitionKey: '',
  processDefinitionName: '',
  processInstanceId: '',
  businessKey: '',
  status: 'all' as 'all' | 'running' | 'finished',
  pageNum: 1,
  pageSize: 20,
})
const applied = ref({ ...query })
const startVisible = ref(false)

const instancesQuery = useQuery({
  queryKey: computed(() => queryKeys.processInstancePage(tenantCode.value, applied.value)),
  queryFn: () => processInstanceApi.page(applied.value),
})

const records = computed(() => instancesQuery.data.value?.records ?? [])
const total = computed(() => instancesQuery.data.value?.total ?? 0)
const runningCount = computed(
  () => records.value.filter((item) => item.status === 'RUNNING').length,
)
const finishedCount = computed(() => Math.max(records.value.length - runningCount.value, 0))

const terminateMutation = useMutation({
  mutationFn: ({ id, reason }: { id: string; reason: string }) =>
    processInstanceApi.terminate(id, reason),
  onSuccess: async () => {
    ElMessage.success('Đã kết thúc công việc')
    await queryClient.invalidateQueries({ queryKey: queryKeys.processInstances(tenantCode.value) })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function search() {
  query.pageNum = 1
  applied.value = { ...query }
}

function reset() {
  Object.assign(query, {
    processDefinitionKey: '',
    processDefinitionName: '',
    processInstanceId: '',
    businessKey: '',
    status: 'all',
    pageNum: 1,
  })
  applied.value = { ...query }
}

function changePage(pageNum: number, pageSize: number) {
  query.pageNum = pageNum
  query.pageSize = pageSize
  applied.value = { ...query }
}

async function terminate(id: string) {
  const reason = await promptRequired('Hãy nhập lý do. Không hoàn tác được.', 'Kết thúc công việc', {
    confirmButtonText: 'Kết thúc',
    cancelButtonText: 'Hủy',
    inputPattern: /\S+/,
    inputErrorMessage: 'Lý do kết thúc không được để trống',
  })
  if (!reason) return
  terminateMutation.mutate({ id, reason })
}

function handleStarted(result: StartProcessResult) {
  void router.push({ name: 'process-instance-detail', params: { id: result.processInstanceId } })
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Đang làm"
      title="Công việc"
      description="Xem việc đang chạy, người đang làm và mã hồ sơ."
    >
      <template v-if="canOperateInstances" #actions>
        <el-button type="primary" @click="startVisible = true">
          <Play :size="16" />Bắt đầu quy trình
        </el-button>
      </template>
    </PageHeader>

    <section class="metric-grid">
      <MetricCard :value="total" label="Tất cả"><template #icon>Σ</template></MetricCard>
      <MetricCard :value="runningCount" label="Đang chạy" tone="success">
        <template #icon>▶</template>
      </MetricCard>
      <MetricCard :value="finishedCount" label="Đã xong" tone="warning">
        <template #icon>✓</template>
      </MetricCard>
    </section>

    <section class="page-actions compact-filter filter-only">
      <el-form class="filter-form filter-form--instances" inline @submit.prevent="search">
        <el-form-item label="Mã quy trình"
          ><el-input v-model="query.processDefinitionKey" clearable
        /></el-form-item>
        <el-form-item label="Tên quy trình"
          ><el-input v-model="query.processDefinitionName" clearable
        /></el-form-item>
        <el-form-item label="Mã công việc"
          ><el-input v-model="query.processInstanceId" clearable
        /></el-form-item>
        <el-form-item label="Mã hồ sơ"
          ><el-input v-model="query.businessKey" clearable
        /></el-form-item>
        <el-form-item label="Trạng thái">
          <el-select v-model="query.status"
            ><el-option label="Tất cả" value="all" /><el-option
              label="Đang chạy"
              value="running" /><el-option label="Đã xong" value="finished"
          /></el-select>
        </el-form-item>
        <el-form-item class="filter-form__actions"
          ><el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button
          ><el-button @click="reset"><RefreshCw :size="16" />Đặt lại</el-button></el-form-item
        >
      </el-form>
    </section>
    <section class="table-panel">
      <el-table
        v-loading="instancesQuery.isFetching.value"
        :data="records"
        height="100%"
        table-layout="fixed"
      >
        <el-table-column
          prop="businessKey"
          label="Mã hồ sơ"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          prop="processDefinitionName"
          label="Tên quy trình"
          min-width="170"
          show-overflow-tooltip
        />
        <el-table-column
          prop="processInstanceId"
          label="Mã công việc"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column prop="startUserId" label="Người bắt đầu" width="120" show-overflow-tooltip />
        <el-table-column label="Trạng thái" width="110" align="center" header-align="center"
          ><template #default="{ row }"
            ><StatusBadge :status="row.status === 'RUNNING' ? 'RUNNING' : 'COMPLETED'" /></template
        ></el-table-column>
        <el-table-column label="Bắt đầu lúc" min-width="175"
          ><template #default="{ row }">{{
            formatDateTime(row.startTime)
          }}</template></el-table-column
        >
        <el-table-column label="Thời lượng" width="130"
          ><template #default="{ row }">{{
            formatDuration(row.durationInMillis)
          }}</template></el-table-column
        >
        <el-table-column label="Thao tác" width="170" fixed="right"
          ><template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="
                router.push({
                  name: 'process-instance-detail',
                  params: { id: row.processInstanceId },
                })
              "
              ><Eye :size="15" />Chi tiết</el-button
            >
            <el-button
              v-if="canOperateInstances && row.status === 'RUNNING'"
              link
              type="danger"
              @click="terminate(row.processInstanceId)"
              ><Ban :size="15" />Kết thúc</el-button
            >
          </template></el-table-column
        >
        <template #empty>
          <ListEmptyState
            title="Chưa có công việc"
            description="Đổi bộ lọc, hoặc bắt đầu một quy trình đã phát hành."
          />
        </template>
      </el-table>
      <TablePagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        aria-label="Phân trang công việc"
        @change="changePage"
      />
    </section>

    <StartProcessDialog v-model="startVisible" @started="handleStarted" />
  </div>
</template>
