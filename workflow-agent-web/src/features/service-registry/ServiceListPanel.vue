<script setup lang="ts">
import { computed } from 'vue'
import type { ServiceView } from './api'
import { serviceNeedsPublish } from './catalogStatus'

const props = defineProps<{
  services: ServiceView[]
  selectedKey: string
  query: string
}>()

const emit = defineEmits<{
  'update:query': [value: string]
  select: [serviceKey: string]
}>()

const visibleServices = computed(() => {
  const needle = props.query.trim().toLowerCase()
  if (!needle) return props.services
  return props.services.filter((service) =>
    [service.serviceKey, service.name].some((value) => value.toLowerCase().includes(needle)),
  )
})
</script>

<template>
  <div class="service-list">
    <el-input
      :model-value="query"
      clearable
      placeholder="Tìm theo mã hoặc tên"
      aria-label="Tìm dịch vụ"
      @update:model-value="emit('update:query', String($event ?? ''))"
    />
    <p v-if="query.trim() && !visibleServices.length" class="service-list__empty">
      Không có dịch vụ khớp
    </p>
    <div v-else class="service-list__items" role="list">
      <button
        v-for="service in visibleServices"
        :key="service.serviceKey"
        type="button"
        role="listitem"
        :data-service="service.serviceKey"
        :aria-current="service.serviceKey === selectedKey ? 'true' : undefined"
        @click="emit('select', service.serviceKey)"
      >
        <span>
          <strong>{{ service.name }}</strong>
          <small>{{ service.serviceKey }}</small>
        </span>
        <span>
          <em>{{ service.operations.length }} thao tác</em>
          <b v-if="serviceNeedsPublish(service.operations)">Cần phát hành</b>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.service-list {
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.service-list__empty {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 15px;
}

.service-list__items {
  display: grid;
  gap: 8px;
}

.service-list__items button {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-height: 64px;
  padding: 14px 16px;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-md);
  color: var(--color-text);
  background: var(--color-surface);
  text-align: left;
  cursor: pointer;
}

.service-list__items button[aria-current='true'] {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.service-list__items span {
  display: grid;
  gap: 4px;
}

.service-list__items strong {
  font-size: 16px;
}

.service-list__items small,
.service-list__items em {
  color: var(--color-text-muted);
  font-size: 14px;
  font-style: normal;
}

.service-list__items b {
  justify-self: end;
  color: var(--color-warning);
  font-size: 14px;
  font-weight: 650;
}
</style>
