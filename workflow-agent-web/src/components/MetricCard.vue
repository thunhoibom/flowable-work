<script setup lang="ts">
withDefaults(
  defineProps<{
    value: number | string
    label: string
    tone?: 'default' | 'success' | 'warning'
  }>(),
  { tone: 'default' },
)
</script>

<template>
  <article class="metric-card" :class="tone === 'default' ? undefined : tone">
    <span aria-hidden="true"><slot name="icon" /></span>
    <div>
      <strong>{{ value }}</strong>
      <small>{{ label }}</small>
    </div>
  </article>
</template>
