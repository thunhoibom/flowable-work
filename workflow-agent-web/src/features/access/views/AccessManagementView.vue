<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw, Search } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { accessApi } from '../api'
import type { TenantMember } from '../types'
import '../styles.css'

// Membership only scopes data to a tenant. Roles (WF_*) are assigned to users in EIM.
const authStore = useAuthStore()
const queryClient = useQueryClient()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const memberKeyword = ref('')
const appliedKeyword = ref('')
const memberDialogVisible = ref(false)
const memberForm = reactive({ username: '' })

const membersQuery = useQuery({
  queryKey: computed(() => queryKeys.tenantMemberList(tenantCode.value, appliedKeyword.value)),
  queryFn: () => accessApi.members(appliedKeyword.value || undefined),
})
const members = computed(() => membersQuery.data.value ?? [])

async function refreshMembers() {
  await queryClient.invalidateQueries({ queryKey: queryKeys.tenantMembers(tenantCode.value) })
}

const addMemberMutation = useMutation({
  mutationFn: async () => {
    if (!memberForm.username.trim()) throw new Error('Hãy nhập tên đăng nhập SSO của người dùng')
    await accessApi.addMember(memberForm.username.trim())
  },
  onSuccess: async () => {
    memberDialogVisible.value = false
    ElMessage.success('Đã thêm thành viên vào tổ chức này')
    await refreshMembers()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

function searchMembers() {
  appliedKeyword.value = memberKeyword.value.trim()
}

function resetMembers() {
  memberKeyword.value = ''
  appliedKeyword.value = ''
}

function addMember() {
  memberForm.username = ''
  memberDialogVisible.value = true
}

async function toggleMember(member: TenantMember) {
  try {
    await accessApi.updateMemberEnabled(member.userId, !member.enabled)
    ElMessage.success(member.enabled ? 'Đã tắt thành viên' : 'Đã bật thành viên')
    await refreshMembers()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
</script>

<template>
  <div class="management-page page-stack access-page">
    <PageHeader
      eyebrow="Tổ chức"
      title="Thành viên"
      description="Thêm người vào tổ chức đang chọn. Vai trò và quyền được gán trên EIM."
    >
      <template #actions
        ><el-tag type="primary" effect="plain">{{ tenantCode }}</el-tag></template
      >
    </PageHeader>

    <section class="access-workspace">
      <div class="access-toolbar">
        <el-form class="filter-form filter-form--members" inline @submit.prevent="searchMembers">
          <el-form-item label="Thành viên">
            <el-input
              v-model="memberKeyword"
              clearable
              placeholder="Tên đăng nhập hoặc tên hiển thị"
            />
          </el-form-item>
          <el-form-item class="filter-form__actions">
            <el-button type="primary" native-type="submit"><Search :size="16" />Tìm</el-button>
            <el-button @click="resetMembers"><RefreshCw :size="16" />Đặt lại</el-button>
          </el-form-item>
        </el-form>
        <el-button type="primary" @click="addMember"><Plus :size="17" />Thêm thành viên</el-button>
      </div>
      <el-table
        class="access-table"
        v-loading="membersQuery.isFetching.value"
        :data="members"
        height="470"
        table-layout="fixed"
      >
        <el-table-column
          prop="displayName"
          label="Thành viên"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          prop="username"
          label="Tên đăng nhập"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column label="Thời điểm tham gia" width="165">
          <template #default="{ row }">{{ formatDateTime(row.joinedAt) }}</template>
        </el-table-column>
        <el-table-column label="Trạng thái" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusBadge :status="row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="Thao tác" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleMember(row)">
              {{ row.enabled ? 'Tắt' : 'Bật' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            title="Chưa có thành viên"
            description="Người dùng cần đăng nhập SSO một lần trước khi được thêm vào tổ chức."
          />
        </template>
      </el-table>
    </section>

    <el-dialog
      v-model="memberDialogVisible"
      title="Thêm thành viên"
      width="min(480px, calc(100vw - 32px))"
    >
      <el-form
        class="dialog-form"
        label-position="top"
        @submit.prevent="addMemberMutation.mutate()"
      >
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="Người dùng cần đăng nhập SSO một lần trước. Quyền của họ do EIM quyết định."
        />
        <el-form-item label="Tên đăng nhập SSO" required>
          <el-input v-model="memberForm.username" placeholder="ví dụ rsa_test1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="addMemberMutation.isPending.value"
          @click="addMemberMutation.mutate()"
          >Thêm</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>
