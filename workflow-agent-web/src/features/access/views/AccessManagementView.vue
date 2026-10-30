<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw, Search, ShieldCheck, UsersRound } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import ListEmptyState from '@/components/ListEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { agentsEnabled } from '@/config/product'
import { APP_PERMISSION, APP_ROLE, hasAccess } from '@/features/auth/authorization'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, joinValues } from '@/utils/format'
import { accessApi } from '../api'
import type { SaveRoleCommand, TenantMember, TenantRole } from '../types'
import '../styles.css'

const authStore = useAuthStore()
const queryClient = useQueryClient()
const tenantCode = computed(() => authStore.user?.tenantCode || '')
const canManageMembers = computed(() =>
  hasAccess(authStore.user, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.memberManage],
  }),
)
const canManageRoles = computed(() =>
  hasAccess(authStore.user, {
    requiredAnyRoles: [APP_ROLE.platformAdministrator],
    requiredAnyPermissions: [APP_PERMISSION.roleManage],
  }),
)
const activeTab = ref(canManageMembers.value ? 'members' : 'roles')
const memberKeyword = ref('')
const appliedKeyword = ref('')
const memberDialogVisible = ref(false)
const roleDialogVisible = ref(false)
const editingMember = ref<TenantMember>()
const editingRoleCode = ref<string>()
const memberForm = reactive({ username: '', roleCodes: [] as string[] })
const roleForm = reactive<SaveRoleCommand>({
  roleCode: '',
  roleName: '',
  description: '',
  enabled: true,
  permissions: [],
})

const membersQuery = useQuery({
  queryKey: computed(() => queryKeys.tenantMemberList(tenantCode.value, appliedKeyword.value)),
  queryFn: () => accessApi.members(appliedKeyword.value || undefined),
  enabled: canManageMembers,
})
const rolesQuery = useQuery({
  queryKey: computed(() => queryKeys.tenantRoles(tenantCode.value)),
  queryFn: accessApi.roles,
  enabled: computed(() => canManageMembers.value || canManageRoles.value),
})
const permissionsQuery = useQuery({
  queryKey: queryKeys.permissions(),
  queryFn: accessApi.permissions,
  enabled: canManageRoles,
})
const permissionOptions = computed(() => {
  const permissions = permissionsQuery.data.value ?? []
  return agentsEnabled
    ? permissions
    : permissions.filter((permission) => !permission.permissionCode.startsWith('agent:'))
})
const members = computed(() => membersQuery.data.value ?? [])
const roles = computed(() => rolesQuery.data.value ?? [])
const memberDialogTitle = computed(() =>
  editingMember.value
    ? `Cấu hình vai trò thành viên · ${editingMember.value.displayName} (${editingMember.value.username})`
    : 'Thêm thành viên',
)

async function refreshAccessData() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.tenantMembers(tenantCode.value) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.tenantRoles(tenantCode.value) }),
  ])
}

const saveMemberMutation = useMutation({
  mutationFn: async () => {
    if (memberForm.roleCodes.length === 0) throw new Error('Hãy chọn ít nhất một vai trò')
    if (editingMember.value) {
      await accessApi.updateMemberRoles(editingMember.value.userId, memberForm.roleCodes)
      return
    }
    if (!memberForm.username.trim()) throw new Error('Hãy nhập tên đăng nhập của người đã đăng ký')
    await accessApi.addMember(memberForm.username.trim(), memberForm.roleCodes)
  },
  onSuccess: async () => {
    memberDialogVisible.value = false
    ElMessage.success(
      editingMember.value ? `Đã cập nhật vai trò của ${editingMember.value.username}` : 'Đã thêm thành viên vào tổ chức này',
    )
    await refreshAccessData()
  },
  onError: (error) => ElMessage.error(getErrorMessage(error)),
})

const saveRoleMutation = useMutation({
  mutationFn: () => {
    if (!roleForm.roleCode.trim() || !roleForm.roleName.trim()) {
      throw new Error('Mã vai trò và tên vai trò không được để trống')
    }
    if (!/^[A-Za-z][A-Za-z0-9_]{1,63}$/.test(roleForm.roleCode.trim())) {
      throw new Error('Mã vai trò phải bắt đầu bằng chữ cái và chỉ gồm chữ cái, số và gạch dưới')
    }
    return accessApi.saveRole({
      ...roleForm,
      roleCode: roleForm.roleCode.trim().toUpperCase(),
      roleName: roleForm.roleName.trim(),
      description: roleForm.description?.trim(),
    })
  },
  onSuccess: async () => {
    roleDialogVisible.value = false
    ElMessage.success(editingRoleCode.value ? 'Đã cập nhật vai trò' : 'Đã tạo vai trò')
    await refreshAccessData()
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
  editingMember.value = undefined
  Object.assign(memberForm, { username: '', roleCodes: ['USER'] })
  memberDialogVisible.value = true
}

function editMember(member: TenantMember) {
  editingMember.value = member
  Object.assign(memberForm, { username: member.username, roleCodes: [...member.roles] })
  memberDialogVisible.value = true
}

async function toggleMember(member: TenantMember) {
  try {
    await accessApi.updateMemberEnabled(member.userId, !member.enabled)
    ElMessage.success(member.enabled ? 'Đã tắt thành viên' : 'Đã bật thành viên')
    await refreshAccessData()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function addRole() {
  editingRoleCode.value = undefined
  Object.assign(roleForm, {
    roleCode: '',
    roleName: '',
    description: '',
    enabled: true,
    permissions: [],
  })
  roleDialogVisible.value = true
}

function editRole(role: TenantRole) {
  editingRoleCode.value = role.roleCode
  Object.assign(roleForm, {
    roleCode: role.roleCode,
    roleName: role.roleName,
    description: role.description || '',
    enabled: role.enabled,
    permissions: [...role.permissions],
  })
  roleDialogVisible.value = true
}
</script>

<template>
  <div class="management-page page-stack access-page">
    <PageHeader
      eyebrow="Quyền"
      title="Thành viên"
      description="Thêm người, gán vai trò và chọn quyền cho tổ chức đang chọn."
    >
      <template #actions
        ><el-tag type="primary" effect="plain">{{ tenantCode }}</el-tag></template
      >
    </PageHeader>

    <section class="access-workspace">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-if="canManageMembers" name="members">
          <template #label
            ><span class="tab-label"><UsersRound :size="16" />Thành viên</span></template>
          >
          <div class="access-toolbar">
            <el-form
              class="filter-form filter-form--members"
              inline
              @submit.prevent="searchMembers"
            >
              <el-form-item label="Thành viên">
                <el-input v-model="memberKeyword" clearable placeholder="Tên đăng nhập hoặc tên hiển thị" />
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
              min-width="140"
              show-overflow-tooltip
            />
            <el-table-column prop="username" label="Tên đăng nhập" min-width="130" show-overflow-tooltip />
            <el-table-column label="Vai trò" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{
                joinValues([...(row.globalRoles || []), ...row.roles]) || '-'
              }}</template>
            </el-table-column>
            <el-table-column label="Thời điểm tham gia" width="165">
              <template #default="{ row }">{{ formatDateTime(row.joinedAt) }}</template>
            </el-table-column>
            <el-table-column label="Trạng thái" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <StatusBadge :status="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="Thao tác" width="148" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="editMember(row)">Cấu hình vai trò</el-button>
                <el-button
                  link
                  :type="row.enabled ? 'danger' : 'success'"
                  @click="toggleMember(row)"
                >
                  {{ row.enabled ? 'Tắt' : 'Bật' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <ListEmptyState
                title="Chưa có thành viên"
                description="Thêm người vào tổ chức, rồi gán vai trò."
              />
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane v-if="canManageRoles" name="roles">
          <template #label
            ><span class="tab-label"><ShieldCheck :size="16" />Vai trò</span></template>
          >
          <div class="access-toolbar align-right">
            <el-button type="primary" @click="addRole"><Plus :size="17" />Thêm vai trò</el-button>
          </div>
          <el-table
            class="access-table"
            v-loading="rolesQuery.isFetching.value"
            :data="roles"
            height="470"
            table-layout="fixed"
          >
            <el-table-column label="Tên vai trò" min-width="170">
              <template #default="{ row }">
                <span class="role-name-cell">
                  <span>{{ row.roleName }}</span>
                  <el-tag v-if="row.builtIn" type="info" size="small" effect="plain"
                    >Có sẵn trong hệ thống</el-tag
                  >
                </span>
              </template>
            </el-table-column>
            <el-table-column
              prop="roleCode"
              label="Mã vai trò"
              min-width="150"
              show-overflow-tooltip
            />
            <el-table-column
              prop="description"
              label="Mô tả"
              min-width="200"
              show-overflow-tooltip
            />
            <el-table-column label="Số quyền" width="88">
              <template #default="{ row }">{{ row.permissions.length }}</template>
            </el-table-column>
            <el-table-column label="Trạng thái" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <StatusBadge :status="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="Thao tác" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="!row.builtIn" link type="primary" @click="editRole(row)"
                  >Sửa</el-button
                >
                <span v-else class="muted-copy">Không sửa được</span>
              </template>
            </el-table-column>
            <template #empty>
              <ListEmptyState
                title="Chưa có vai trò"
                description="Tạo vai trò, chọn quyền, rồi gán cho thành viên."
              />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="memberDialogVisible" :title="memberDialogTitle" width="520px">
      <el-form class="dialog-form" label-position="top">
        <el-alert
          v-if="editingMember"
          type="info"
          :closable="false"
          show-icon
          :title="`Đang cấu hình ${editingMember.displayName} (${editingMember.username})`"
        />
        <el-form-item label="Tên đăng nhập" required>
          <el-input
            v-model="memberForm.username"
            :disabled="Boolean(editingMember)"
            placeholder="Tên đăng nhập đã đăng ký"
          />
        </el-form-item>
        <el-form-item label="Vai trò" required>
          <el-select v-model="memberForm.roleCodes" multiple clearable>
            <el-option
              v-for="role in roles"
              :key="role.roleCode"
              :label="role.roleName"
              :value="role.roleCode"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="saveMemberMutation.isPending.value"
          @click="saveMemberMutation.mutate()"
          >Lưu</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="roleDialogVisible"
      :title="editingRoleCode ? 'Sửa vai trò' : 'Thêm vai trò'"
      width="620px"
    >
      <el-form class="dialog-form" label-position="top">
        <div class="dialog-grid">
          <el-form-item label="Mã vai trò" required>
            <el-input
              v-model="roleForm.roleCode"
              :disabled="Boolean(editingRoleCode)"
              placeholder="ví dụ APPROVER"
            />
          </el-form-item>
          <el-form-item label="Tên vai trò" required>
            <el-input v-model="roleForm.roleName" />
          </el-form-item>
        </div>
        <el-form-item label="Mô tả">
          <el-input
            v-model="roleForm.description"
            type="textarea"
            :rows="2"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="Phạm vi quyền">
          <el-select v-model="roleForm.permissions" multiple clearable filterable>
            <el-option
              v-for="permission in permissionOptions"
              :key="permission.permissionCode"
              :label="permission.permissionName"
              :value="permission.permissionCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Bật"><el-switch v-model="roleForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="saveRoleMutation.isPending.value"
          @click="saveRoleMutation.mutate()"
          >Lưu</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>
