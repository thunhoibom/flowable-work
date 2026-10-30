<script setup lang="ts">
withDefaults(
  defineProps<{
    align?: 'left' | 'center'
  }>(),
  {
    align: 'left',
  },
)
</script>

<template>
  <div class="table-tag-cell" :class="`table-tag-cell--${align}`">
    <slot />
  </div>
</template>

<style scoped>
.table-tag-cell {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 25px;
}

.table-tag-cell--left {
  justify-content: flex-start;
}

.table-tag-cell--center {
  justify-content: center;
}

.table-tag-cell :deep(.el-tag),
.table-tag-cell :deep(.status-badge) {
  flex: 0 0 auto;
}
</style>
