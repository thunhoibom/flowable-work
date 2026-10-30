import { ref } from 'vue'
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  const mobileNavigationOpen = ref(false)

  function openMobileNavigation() {
    mobileNavigationOpen.value = true
  }

  function closeMobileNavigation() {
    mobileNavigationOpen.value = false
  }

  return { mobileNavigationOpen, openMobileNavigation, closeMobileNavigation }
})
