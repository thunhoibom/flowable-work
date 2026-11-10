<script setup lang="ts">
import { computed, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { canWriteDefinitions } from '@/features/auth/authorization'
import { useAuthStore } from '@/stores/auth'
import EventModelPanel from './EventModelPanel.vue'
import EventWaitsPanel from './EventWaitsPanel.vue'
import RestChannelPanel from './RestChannelPanel.vue'

const tab = ref<'events' | 'channels' | 'waits'>('events')
/** Bumped when events change, so the other tabs reload their event choices. */
const eventsVersion = ref(0)
const authStore = useAuthStore()
/** Operators and viewers read the library and the waits; only designers change it. */
const readOnly = computed(() => !canWriteDefinitions(authStore.user))
</script>

<template>
  <div class="page-stack">
    <PageHeader
      eyebrow="Tích hợp"
      title="Sự kiện"
      description="Khai báo sự kiện nghiệp vụ và kênh nhận. Bước chờ trong quy trình chỉ chọn sự kiện."
    />
    <el-tabs v-model="tab">
      <el-tab-pane label="Sự kiện" name="events">
        <EventModelPanel :read-only="readOnly" @changed="eventsVersion++" />
      </el-tab-pane>
      <el-tab-pane label="Kênh nhận" name="channels" lazy>
        <RestChannelPanel :events-version="eventsVersion" :read-only="readOnly" />
      </el-tab-pane>
      <el-tab-pane label="Đang chờ" name="waits" lazy>
        <EventWaitsPanel :events-version="eventsVersion" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
