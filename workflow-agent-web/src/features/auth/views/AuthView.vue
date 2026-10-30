<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'
import { ArrowRight, CheckCircle2, KeyRound, LockKeyhole, UserRound, Workflow } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { resolvePostAuthRedirect } from '../redirect'

type AuthMode = 'login' | 'register'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const mode = ref<AuthMode>('login')
const submitting = ref(false)
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  displayName: '',
})

const validateConfirmPassword: FormItemRule['validator'] = (_rule, value, callback) => {
  if (!value) {
    callback(new Error('Hãy nhập lại mật khẩu'))
    return
  }
  if (value !== registerForm.password) {
    callback(new Error('Hai lần nhập mật khẩu không khớp'))
    return
  }
  callback()
}

const loginRules: FormRules = {
  username: [{ required: true, message: 'Hãy nhập tên đăng nhập', trigger: 'blur' }],
  password: [{ required: true, message: 'Hãy nhập mật khẩu', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: 'Hãy nhập tên đăng nhập', trigger: 'blur' },
    { min: 3, max: 64, message: 'Tên đăng nhập cần 3–64 ký tự', trigger: 'blur' },
  ],
  password: [
    { required: true, message: 'Hãy nhập mật khẩu', trigger: 'blur' },
    { min: 8, max: 128, message: 'Mật khẩu cần ít nhất 8 ký tự', trigger: 'blur' },
  ],
  confirmPassword: [{ required: true, validator: validateConfirmPassword, trigger: 'blur' }],
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

async function submitRegister() {
  if (!registerFormRef.value) return
  try {
    await registerFormRef.value.validate()
    submitting.value = true
    await authStore.register({
      username: registerForm.username,
      password: registerForm.password,
      displayName: registerForm.displayName || undefined,
    })
    ElMessage.success('Đăng ký thành công, đã đăng nhập')
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
          <span>{{ mode === 'login' ? 'Chào mừng trở lại' : 'Tạo tài khoản làm việc' }}</span>
          <h2>{{ mode === 'login' ? 'Đăng nhập Workflow Agent' : 'Đăng ký tài khoản mới' }}</h2>
          <p>
            {{
              mode === 'login'
                ? 'Nhập tài khoản để bắt đầu.'
                : 'Sau khi đăng ký, bạn vào thẳng trang làm việc.'
            }}
          </p>
        </header>

        <div class="auth-switch" role="tablist" aria-label="Đăng nhập hoặc đăng ký">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="{ active: mode === 'login' }"
            @click="mode = 'login'"
          >
            Đăng nhập
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="{ active: mode === 'register' }"
            @click="mode = 'register'"
          >
            Đăng ký
          </button>
        </div>

        <el-form
          v-if="mode === 'login'"
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

        <el-form
          v-else
          ref="registerFormRef"
          :model="registerForm"
          :rules="registerRules"
          label-position="top"
          class="auth-form"
          @submit.prevent="submitRegister"
        >
          <el-alert
            class="registration-notice"
            type="info"
            :closable="false"
            show-icon
            title="Tài khoản mới là người dùng thường. Chỉ người quản trị mới gán quyền quản trị."
          />
          <div class="auth-form-grid">
            <el-form-item label="Tên đăng nhập" prop="username">
              <el-input
                v-model="registerForm.username"
                size="large"
                autocomplete="username"
                placeholder="3–64 ký tự"
              >
                <template #prefix><UserRound :size="18" /></template>
              </el-input>
            </el-form-item>
            <el-form-item label="Tên hiển thị" prop="displayName">
              <el-input v-model="registerForm.displayName" size="large" placeholder="Không bắt buộc" />
            </el-form-item>
          </div>
          <div class="auth-form-grid auth-credential-grid">
            <el-form-item label="Mật khẩu" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                size="large"
                autocomplete="new-password"
                show-password
                placeholder="Ít nhất 8 ký tự"
              >
                <template #prefix><LockKeyhole :size="18" /></template>
              </el-input>
            </el-form-item>
            <el-form-item label="Xác nhận mật khẩu" prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                size="large"
                autocomplete="new-password"
                show-password
                placeholder="Nhập lại mật khẩu"
                @keyup.enter="submitRegister"
              >
                <template #prefix><LockKeyhole :size="18" /></template>
              </el-input>
            </el-form-item>
          </div>
          <el-button
            type="primary"
            size="large"
            native-type="submit"
            :loading="submitting"
            class="auth-submit"
          >
            Đăng ký <ArrowRight v-if="!submitting" :size="17" />
          </el-button>
        </el-form>

        <p class="auth-footnote">Sau khi đăng nhập, bạn chỉ làm việc trong tổ chức hiện tại.</p>
      </div>
    </section>
  </main>
</template>
