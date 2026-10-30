<script setup lang="ts">
import type { Component } from 'vue'
import { Inbox } from '@lucide/vue'

defineProps<{
  title: string
  description?: string
  icon?: Component
  compact?: boolean
}>()
</script>

<template>
  <div class="list-empty-state" :class="{ 'is-compact': compact }">
    <span class="list-empty-state__icon" aria-hidden="true">
      <component :is="icon || Inbox" :size="28" :stroke-width="1.8" />
    </span>
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="list-empty-state__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.list-empty-state {
  width: 100%;
  min-height: clamp(230px, 28vh, 310px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 32px 24px;
  color: var(--color-text-muted);
  text-align: center;
}

.list-empty-state.is-compact {
  min-height: 168px;
  padding-block: 24px;
}

.list-empty-state__icon {
  width: 54px;
  height: 54px;
  display: grid;
  place-items: center;
  margin-bottom: 2px;
  border: 1px solid var(--color-border-soft);
  border-radius: 18px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  box-shadow: var(--shadow-panel);
}

.list-empty-state strong {
  color: var(--color-text);
  font-size: 15px;
  font-weight: 700;
}

.list-empty-state p {
  max-width: 460px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.65;
}

.list-empty-state__actions {
  margin-top: 5px;
}
</style>
