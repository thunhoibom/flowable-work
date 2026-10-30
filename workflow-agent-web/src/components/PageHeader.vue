<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description: string
}>()
</script>

<template>
  <section class="page-hero compact-hero">
    <div>
      <span class="eyebrow">{{ eyebrow }}</span>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="hero-actions">
      <slot name="actions" />
    </div>
  </section>
</template>
