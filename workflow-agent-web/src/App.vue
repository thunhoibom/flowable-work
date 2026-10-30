<script setup lang="ts">
import { ElConfigProvider } from 'element-plus'
import vi from 'element-plus/es/locale/lang/vi'
</script>

<template>
  <ElConfigProvider :locale="vi">
    <RouterView />
  </ElConfigProvider>
</template>
