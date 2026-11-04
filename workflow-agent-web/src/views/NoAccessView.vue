<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { LogOut } from '@lucide/vue'
import { APP_ROLE, readAuthSession } from '@/features/auth'
import { useAuthStore } from '@/stores/auth'

// Shown when the user signed in through SSO but EIM grants no Workflow Agent role (WF_*).
const router = useRouter()
const authStore = useAuthStore()
const session = readAuthSession()
const username = session?.username ?? ''
const roles = computed(() => session?.roles ?? [])
const requiredRoles = Object.values(APP_ROLE).join(', ')
const signingOut = ref(false)

async function signOut() {
  signingOut.value = true
  try {
    await authStore.logout()
  } finally {
    signingOut.value = false
    await router.replace({ name: 'auth' })
  }
}
</script>

<template>
  <main class="standalone-state no-access-state">
    <strong>403</strong>
    <h1>Tài khoản chưa được cấp quyền vào Workflow Agent</h1>
    <p>
      Tài khoản <b>{{ username }}</b> đăng nhập thành công nhưng chưa có vai trò nào của ứng dụng
      trên EIM. Hãy nhờ quản trị EIM gán một trong các vai trò: <code>{{ requiredRoles }}</code>.
    </p>
    <p>
      Vai trò hiện có trong token:
      <code>{{ roles.length ? roles.join(', ') : '(không có)' }}</code>
    </p>
    <el-button type="primary" :loading="signingOut" @click="signOut">
      <LogOut :size="16" />Đăng nhập tài khoản khác
    </el-button>
  </main>
</template>

<style scoped>
.no-access-state {
  gap: 12px;
  padding: 16px;
  text-align: center;
}
.no-access-state p {
  max-width: 560px;
  margin: 0;
  color: var(--muted);
  line-height: 1.6;
}
.no-access-state code {
  word-break: break-word;
}
</style>
