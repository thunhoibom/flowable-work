<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ExternalLink, RefreshCw } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TablePagination from '@/components/TablePagination.vue'
import { formatDateTime } from '@/utils/format'
import { eventRegistryApi, type EventModelView, type EventWaitView } from './api'

const props = withDefaults(defineProps<{ eventsVersion?: number }>(), { eventsVersion: 0 })

const events = ref<EventModelView[]>([])
const eventKey = ref('')
const rows = ref<EventWaitView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const published = computed(() => events.value.filter((event) => event.publishedVersion))

async function loadEvents() {
  try {
    events.value = await eventRegistryApi.events()
    if (!published.value.some((event) => event.key === eventKey.value)) {
      eventKey.value = published.value[0]?.key ?? ''
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function loadWaits() {
  if (!eventKey.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await eventRegistryApi.waits(eventKey.value, page.value, size.value)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function changeEvent(key: string) {
  eventKey.value = key
  page.value = 1
  void loadWaits()
}

function changePage(nextPage: number, nextSize: number) {
  page.value = nextPage
  size.value = nextSize
  void loadWaits()
}

watch(
  () => props.eventsVersion,
  async () => {
    await loadEvents()
    await loadWaits()
  },
)

onMounted(async () => {
  await loadEvents()
  await loadWaits()
})
</script>

<template>
  <div class="registry-panel">
    <div class="registry-toolbar registry-toolbar--start">
      <label class="registry-filter">
        <span>Sự kiện</span>
        <select
          class="registry-input"
          data-field="wait-event"
          :value="eventKey"
          @change="changeEvent(($event.target as HTMLSelectElement).value)"
        >
          <option v-for="event in published" :key="event.key" :value="event.key">
            {{ event.name }} ({{ event.key }})
          </option>
        </select>
      </label>
      <el-button data-action="reload-waits" :loading="loading" @click="loadWaits">
        <RefreshCw :size="16" />Tải lại
      </el-button>
    </div>

    <section class="table-panel elevated-panel">
      <el-table
        v-loading="loading"
        :data="rows"
        :row-key="(row: EventWaitView) => row.executionId + row.activityId"
        table-layout="fixed"
      >
        <el-table-column label="STT" width="64" align="center">
          <template #default="{ $index }">
            <span class="registry-index">{{ (page - 1) * size + $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Công việc" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span data-row="wait">
              <router-link
                :to="{ name: 'process-instance-detail', params: { id: row.processInstanceId } }"
              >
                {{ row.businessKey || row.processInstanceId }}
              </router-link>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Quy trình" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.processDefinitionName || row.processDefinitionKey }}</template>
        </el-table-column>
        <el-table-column label="Bước" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.activityName || row.activityId }}</template>
        </el-table-column>
        <el-table-column label="Execution" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.executionId }}</code></template>
        </el-table-column>
        <el-table-column label="Đang chờ từ" width="170">
          <template #default="{ row }">{{ formatDateTime(row.since) }}</template>
        </el-table-column>
        <el-table-column label="Hành động" width="140" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <router-link
                class="el-button el-button--primary is-link"
                data-action="open-instance"
                :to="{ name: 'process-instance-detail', params: { id: row.processInstanceId } }"
              >
                <ExternalLink :size="14" />Mở công việc
              </router-link>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            title="Không có công việc nào đang chờ sự kiện này"
            description="Công việc dừng ở bước chờ sự kiện sẽ hiện ở đây cho tới khi sự kiện đến."
          />
        </template>
      </el-table>
      <TablePagination
        v-if="total > 0"
        :total="total"
        :current-page="page"
        :page-size="size"
        aria-label="Phân trang công việc đang chờ"
        @change="changePage"
      />
    </section>
  </div>
</template>

<style scoped src="./registry.css"></style>
