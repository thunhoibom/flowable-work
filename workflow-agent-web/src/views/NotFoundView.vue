<script setup lang="ts">
import { ArrowLeft } from '@lucide/vue'
</script>

<template>
  <main class="standalone-state">
    <strong>404</strong>
    <h1>Không tìm thấy trang</h1>
    <RouterLink to="/process-definitions"><ArrowLeft :size="16" />Về quy trình</RouterLink>
  </main>
</template>
