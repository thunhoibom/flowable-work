<script setup lang="ts">
import { computed } from 'vue'
import { getStatusPresentation, type StatusTone } from '@/utils/status'

const props = defineProps<{
  status: string | boolean
  label?: string
  tone?: StatusTone
}>()

const presentation = computed(() => getStatusPresentation(props.status))
</script>

<template>
  <el-tag
    class="status-badge"
    :type="tone || presentation.tone"
    :data-tone="tone || presentation.tone"
    effect="light"
    size="small"
    round
    :title="String(status)"
  >
    {{ label || presentation.label }}
  </el-tag>
</template>

<style scoped>
.status-badge {
  width: 74px;
  min-width: 74px;
  box-sizing: border-box;
  height: 25px;
  justify-content: center;
  padding-inline: 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
}

.status-badge[data-tone='primary'] {
  --el-tag-bg-color: var(--color-primary-soft);
  --el-tag-border-color: #bfd5ff;
  --el-tag-text-color: var(--color-primary-strong);
}

.status-badge[data-tone='success'] {
  --el-tag-bg-color: var(--color-success-soft);
  --el-tag-border-color: var(--color-success-border);
  --el-tag-text-color: var(--color-success);
}

.status-badge[data-tone='warning'] {
  --el-tag-bg-color: var(--color-warning-soft);
  --el-tag-border-color: var(--color-warning-border);
  --el-tag-text-color: var(--color-warning);
}

.status-badge[data-tone='danger'] {
  --el-tag-bg-color: var(--color-danger-soft);
  --el-tag-border-color: var(--color-danger-border);
  --el-tag-text-color: var(--color-danger);
}

.status-badge[data-tone='info'] {
  --el-tag-bg-color: var(--color-surface-muted);
  --el-tag-border-color: var(--color-border);
  --el-tag-text-color: var(--color-text-muted);
}

:deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}
</style>
