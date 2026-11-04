<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowRight, CheckCircle2, KeyRound, LockKeyhole, UserRound, Workflow } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { resolvePostAuthRedirect } from '../redirect'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const submitting = ref(false)
const loginFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })

const loginRules: FormRules = {
  username: [{ required: true, message: 'Hãy nhập tên đăng nhập', trigger: 'blur' }],
  password: [{ required: true, message: 'Hãy nhập mật khẩu', trigger: 'blur' }],
}

async function enterWorkspace() {
  await router.replace(resolvePostAuthRedirect(route.query.redirect))
}

async function submitLogin() {
  if (!loginFormRef.value) return
  try {
    await loginFormRef.value.validate()
    submitting.value = true
    await authStore.login(loginForm)
    ElMessage.success('Đăng nhập thành công')
    await enterWorkspace()
  } catch (error) {
    if (error instanceof Error) ElMessage.error(getErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

</script>

<template>
  <main class="auth-page">
    <section class="auth-intro">
      <div class="auth-brand">
        <span class="auth-brand-mark"><Workflow :size="25" /></span>
        <div>
          <strong>Workflow Agent</strong>
          <span>Làm việc cùng Flowable</span>
        </div>
      </div>

      <div class="auth-intro-copy">
        <span class="auth-eyebrow">WORKFLOW · RULES</span>
        <h1>Làm việc với quy trình<br />và nhiệm vụ.</h1>
        <p>Bắt đầu quy trình, theo dõi công việc và giao việc cho đúng người.</p>
      </div>

      <div class="auth-benefits">
        <span><CheckCircle2 :size="17" />Mỗi tổ chức một không gian riêng</span>
        <span><CheckCircle2 :size="17" />Đăng nhập an toàn</span>
        <span><CheckCircle2 :size="17" />Chạy trên Flowable</span>
      </div>
    </section>

    <section class="auth-form-side">
      <div class="auth-card">
        <div class="auth-card-icon"><KeyRound :size="22" /></div>
        <header class="auth-card-header">
          <span>Chào mừng trở lại</span>
          <h2>Đăng nhập Workflow Agent</h2>
          <p>Đăng nhập bằng tài khoản SSO VETC.</p>
        </header>

        <el-form
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          label-position="top"
          class="auth-form"
          @submit.prevent="submitLogin"
        >
          <el-form-item label="Tên đăng nhập" prop="username">
            <el-input
              v-model="loginForm.username"
              size="large"
              autocomplete="username"
              placeholder="Nhập tên đăng nhập"
            >
              <template #prefix><UserRound :size="18" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="Mật khẩu" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              size="large"
              autocomplete="current-password"
              show-password
              placeholder="Nhập mật khẩu"
              @keyup.enter="submitLogin"
            >
              <template #prefix><LockKeyhole :size="18" /></template>
            </el-input>
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            native-type="submit"
            :loading="submitting"
            class="auth-submit"
          >
            Đăng nhập <ArrowRight v-if="!submitting" :size="17" />
          </el-button>
        </el-form>

        <p class="auth-footnote">Sau khi đăng nhập, bạn chỉ làm việc trong tổ chức hiện tại.</p>
      </div>
    </section>
  </main>
</template>
