<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Boxes,
  CheckCircle2,
  Clock3,
  FilePlus2,
  Play,
  Plus,
  RefreshCw,
  Search,
  SquarePen,
  Trash2,
} from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TablePagination from '@/components/TablePagination.vue'
import MetricCard from '@/components/MetricCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import TableTagCell from '@/components/TableTagCell.vue'
import {
  canOperateInstances as isInstanceOperable,
  canWriteDefinitions as isDefinitionWritable,
} from '@/features/auth/authorization'
import { StartProcessDialog, type StartProcessResult } from '@/features/process-instance'
import { useAuthStore } from '@/stores/auth'
import { createBlankBpmn } from '@/utils/bpmn'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime, formatVersion } from '@/utils/format'
import { definitionApi } from '../api'
import { normalizeProcessIdentity } from '../domain'
import type { PublishStatus } from '../types'

const router = useRouter()
const queryClient = useQueryClient()
const authStore = useAuthStore()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canWriteDefinitions = computed(() => isDefinitionWritable(authStore.user))
const canOperateInstances = computed(() => isInstanceOperable(authStore.user))
const query = reactive({
  processDefinitionKey: '',
  processDefinitionName: '',
  publishStatus: 'all' as PublishStatus,
  pageNum: 1,
  pageSize: 20,
})
const applied = ref({ ...query })
const createVisible = ref(false)
const startVisible = ref(false)
const startProcessDefinitionKey = ref<string>()
const createForm = reactive({ processDefinitionKey: '', processDefinitionName: '' })

const definitionsQuery = useQuery({
  queryKey: computed(() => queryKeys.processDefinitionPage(tenantCode.value, applied.value)),
  queryFn: () => definitionApi.page(applied.value),
})

const records = computed(() => definitionsQuery.data.value?.records ?? [])
const total = computed(() => definitionsQuery.data.value?.total ?? 0)
const publishedCount = computed(() => records.value.filter((item) => item.activeVersion).length)
const draftCount = computed(() => Math.max(records.value.length - publishedCount.value, 0))

const deleteMutation = useMutation({
  mutationFn: definitionApi.deleteAll,
  onSuccess: async () => {
    ElMessage.success('Đã xóa quy trình')
    await queryClient.invalidateQueries({
      queryKey: queryKeys.processDefinitions(tenantCode.value),
    })
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

const createMutation = useMutation({
  mutationFn: async () => {
    const { key, name } = normalizeProcessIdentity(
      createForm.processDefinitionKey,
      createForm.processDefinitionName,
    )
    if (await definitionApi.exists(key)) throw new Error(`Mã quy trình đã tồn tại: ${key}`)
    return { key, name, xml: createBlankBpmn(key, name) }
  },
  onSuccess: ({ key, name, xml }) => {
    createVisible.value = false
    router.push({ name: 'process-designer', query: { key, name, draft: encodeURIComponent(xml) } })
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
    publishStatus: 'all',
    pageNum: 1,
  })
  applied.value = { ...query }
}

function changePage(pageNum: number, pageSize: number) {
  query.pageNum = pageNum
  query.pageSize = pageSize
  applied.value = { ...query }
}

async function remove(key: string, name: string) {
  const confirmed = await confirmAction(
    `Xóa mọi phiên bản của “${name}”? Công việc đang chạy có thể khiến việc xóa không thực hiện được.`,
    'Xóa quy trình',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  deleteMutation.mutate(key)
}

function openStart(processDefinitionKey?: string) {
  startProcessDefinitionKey.value = processDefinitionKey
  startVisible.value = true
}

function handleStarted(result: StartProcessResult) {
  void router.push({ name: 'process-instance-detail', params: { id: result.processInstanceId } })
}
</script>

<template>
  <div class="management-page page-stack">
    <PageHeader
      eyebrow="Mô hình"
      title="Quy trình"
      description="Tạo quy trình, phát hành phiên bản, rồi bắt đầu một công việc."
    >
      <template v-if="canWriteDefinitions || canOperateInstances" #actions>
        <el-button v-if="canWriteDefinitions" @click="createVisible = true">
          <Plus :size="17" />Tạo quy trình
        </el-button>
        <el-button v-if="canOperateInstances" type="primary" @click="openStart()">
          <Play :size="16" />Bắt đầu quy trình
        </el-button>
      </template>
    </PageHeader>

    <section class="metric-grid">
      <MetricCard :value="total" label="Quy trình">
        <template #icon><Boxes :size="18" /></template>
      </MetricCard>
      <MetricCard :value="publishedCount" label="Đã phát hành" tone="success">
        <template #icon><CheckCircle2 :size="18" /></template>
      </MetricCard>
      <MetricCard :value="draftCount" label="Bản nháp" tone="warning">
        <template #icon><Clock3 :size="18" /></template>
      </MetricCard>
    </section>

    <section class="page-actions compact-filter definition-filter">
      <el-form class="filter-form filter-form--definitions" inline @submit.prevent="search">
        <el-form-item label="Mã quy trình">
          <el-input
            v-model="query.processDefinitionKey"
            clearable
            placeholder="ví dụ expense_approval"
          />
        </el-form-item>
        <el-form-item label="Tên quy trình">
          <el-input v-model="query.processDefinitionName" clearable placeholder="Nhập tên quy trình" />
        </el-form-item>
        <el-form-item label="Trạng thái">
          <el-select
            v-model="query.publishStatus"
            v-accessible-label="'Trạng thái'"
            aria-label="Trạng thái"
          >
            <el-option label="Tất cả" value="all" />
            <el-option label="Đã phát hành" value="published" />
            <el-option label="Bản nháp" value="unpublished" />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-form__actions">
          <div class="filter-actions">
            <el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button>
            <el-button @click="reset"><RefreshCw :size="16" />Đặt lại</el-button>
          </div>
        </el-form-item>
      </el-form>
    </section>

    <section class="table-panel elevated-panel">
      <el-table
        class="definition-table"
        v-loading="definitionsQuery.isFetching.value"
        :data="records"
        height="100%"
        table-layout="fixed"
      >
        <el-table-column
          prop="processDefinitionName"
          label="Quy trình"
          min-width="260"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="definition-name-cell">
              <span class="definition-avatar">{{
                row.processDefinitionName?.slice(0, 1) || 'Q'
              }}</span>
              <div>
                <strong>{{ row.processDefinitionName }}</strong>
                <small>{{ row.processDefinitionKey }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Đang dùng" width="140" header-align="center">
          <template #default="{ row }">
            <TableTagCell>
              <el-tag
                class="version-tag"
                :type="row.activeVersion ? 'success' : 'info'"
                effect="light"
                round
              >
                {{ row.activeVersion ? formatVersion(row.activeVersion) : 'Bản nháp' }}
              </el-tag>
            </TableTagCell>
          </template>
        </el-table-column>
        <el-table-column label="Mới nhất" width="130" header-align="center">
          <template #default="{ row }">
            <TableTagCell>
              <el-tag class="version-tag" type="warning" effect="light" round>
                {{ formatVersion(row.latestVersion) }}
              </el-tag>
            </TableTagCell>
          </template>
        </el-table-column>
        <el-table-column label="Cập nhật lúc" width="180">
          <template #default="{ row }">{{ formatDateTime(row.latestDeployTime) }}</template>
        </el-table-column>
        <el-table-column v-if="canWriteDefinitions || canOperateInstances" label="Thao tác" width="240">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-if="canOperateInstances && row.activeVersion"
                link
                type="success"
                @click="openStart(row.processDefinitionKey)"
              >
                <Play :size="14" />Bắt đầu
              </el-button>
              <el-button
                v-if="canWriteDefinitions"
                link
                type="primary"
                @click="
                  router.push({
                    name: 'process-designer',
                    query: { key: row.processDefinitionKey },
                  })
                "
              >
                <SquarePen :size="14" />Thiết kế
              </el-button>
              <el-button
                v-if="canWriteDefinitions"
                link
                type="danger"
                @click="remove(row.processDefinitionKey, row.processDefinitionName)"
              >
                <Trash2 :size="14" />Xóa
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            title="Chưa có quy trình"
            description="Tạo quy trình đầu tiên. Phát hành một phiên bản, rồi bắt đầu công việc."
            :icon="FilePlus2"
          >
            <el-button v-if="canWriteDefinitions" type="primary" @click="createVisible = true">
              <Plus :size="16" />Tạo quy trình
            </el-button>
          </ListEmptyState>
        </template>
      </el-table>
      <TablePagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        aria-label="Phân trang quy trình"
        @change="changePage"
      />
    </section>

    <el-dialog v-model="createVisible" title="Tạo quy trình" width="480px" destroy-on-close>
      <el-form label-position="top" @submit.prevent="createMutation.mutate()">
        <el-form-item label="Mã quy trình" required>
          <el-input
            v-model="createForm.processDefinitionKey"
            maxlength="64"
            placeholder="expense_approval"
          />
        </el-form-item>
        <el-form-item label="Tên quy trình" required>
          <el-input
            v-model="createForm.processDefinitionName"
            maxlength="128"
            placeholder="Phê duyệt chi phí"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="createMutation.isPending.value"
          @click="createMutation.mutate()"
        >
          Tạo và thiết kế
        </el-button>
      </template>
    </el-dialog>

    <StartProcessDialog
      v-model="startVisible"
      :initial-process-definition-key="startProcessDefinitionKey"
      @started="handleStarted"
    />
  </div>
</template>
