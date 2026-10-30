import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElOption,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeline,
  ElTimelineItem,
  ElTooltip,
} from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { ApiError } from './api/http'
import './styles/main.css'
import './styles/design-system.css'
import { accessibleLabel } from './directives/accessibility'

const app = createApp(App)
const pinia = createPinia()
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status && error.status < 500) return false
        return failureCount < 1
      },
    },
    mutations: {
      retry: false,
    },
  },
})

app.use(pinia)
app.use(router)
app.use(VueQueryPlugin, { queryClient })
app.directive('accessible-label', accessibleLabel)
const elementComponents = [
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeline,
  ElTimelineItem,
  ElTooltip,
]
elementComponents.forEach((component) => app.component(component.name!, component))
app.use(ElLoading)

window.addEventListener('workflow-auth:unauthorized', async () => {
  const { useAuthStore } = await import('@/stores/auth')
  useAuthStore(pinia).logout(false)
  if (router.currentRoute.value.name !== 'auth') {
    await router.replace({
      name: 'auth',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
})

app.mount('#app')
