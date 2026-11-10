<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Eye, Plus, RefreshCw, Rocket, SquarePen, Trash2, Webhook } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TableTagCell from '@/components/TableTagCell.vue'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime } from '@/utils/format'
import {
  eventRegistryApi,
  type EventModelView,
  type KeyDetection,
  type RestChannelView,
} from './api'
import {
  KEY_DETECTIONS,
  allowedEvents,
  curlExample,
  sampleBody,
  validateChannel,
  webhookUrl,
  type ChannelForm,
} from './channelDraft'
import { KEY_PATTERN } from './eventModelDraft'
import { registryStatus, registryStatusLabel, registryStatusTag } from './registryStatus'

const props = withDefaults(
  defineProps<{
    eventsVersion?: number
    /** Viewers and operators: no create, save, publish or delete. */ readOnly?: boolean
  }>(),
  { eventsVersion: 0, readOnly: false },
)

const channels = ref<RestChannelView[]>([])
const events = ref<EventModelView[]>([])
const loading = ref(false)
const saving = ref(false)
const publishingKey = ref('')
const editorVisible = ref(false)
/** Key of the channel open in the editor. Null while creating a new one. */
const editingKey = ref<string | null>(null)
/** Key of the channel whose webhook details are open. */
const webhookKey = ref('')
const form = reactive<ChannelForm & { description: string }>({
  key: '',
  name: '',
  description: '',
  detection: 'FIXED',
  detectionValue: '',
  allowedEventKeys: [],
  userIds: [],
  groupIds: [],
})

const publishedEvents = computed(() => events.value.filter((event) => event.publishedVersion))
const selected = computed(() =>
  editingKey.value === null
    ? undefined
    : channels.value.find((channel) => channel.key === editingKey.value),
)
const isNew = computed(() => editingKey.value === null)
const keyTaken = computed(
  () => isNew.value && channels.value.some((channel) => channel.key === form.key.trim()),
)
const problems = computed(() => {
  const list = validateChannel({ ...form, key: form.key.trim() })
  if (keyTaken.value) list.unshift('Kênh đã tồn tại')
  return list
})
const dirty = computed(() => {
  if (isNew.value) return true
  const current = selected.value
  if (!current) return false
  return (
    form.name !== current.name ||
    form.description !== (current.description ?? '') ||
    form.detection !== current.detection ||
    form.detectionValue !== current.detectionValue ||
    JSON.stringify(allowedEvents(form)) !== JSON.stringify(current.allowedEventKeys) ||
    JSON.stringify(form.userIds) !== JSON.stringify(current.userIds) ||
    JSON.stringify(form.groupIds) !== JSON.stringify(current.groupIds)
  )
})
const unpublishedEvents = computed(() => unpublishedOf(allowedEvents(form)))
const canSave = computed(
  () => !props.readOnly && !problems.value.length && dirty.value && !saving.value,
)
const canPublishInEditor = computed(
  () =>
    !isNew.value &&
    !dirty.value &&
    !unpublishedEvents.value.length &&
    Boolean(selected.value && canPublish(selected.value)),
)
const webhookChannel = computed(() =>
  channels.value.find((channel) => channel.key === webhookKey.value),
)

function unpublishedOf(keys: string[]) {
  return keys.filter((key) => !publishedEvents.value.some((event) => event.key === key))
}

function channelEvents(channel: Pick<RestChannelView, 'detection' | 'detectionValue' | 'allowedEventKeys'>) {
  return allowedEvents(channel)
}

function canPublish(channel: RestChannelView) {
  return channel.changed && !unpublishedOf(channelEvents(channel)).length
}

function detectionLabel(channel: RestChannelView) {
  const label = KEY_DETECTIONS.find((mode) => mode.value === channel.detection)?.label ?? channel.detection
  return channel.detection === 'FIXED' || !channel.detectionValue
    ? label
    : `${label}: ${channel.detectionValue}`
}

function callersLabel(channel: RestChannelView) {
  const callers = [...channel.userIds, ...channel.groupIds]
  return callers.length ? callers.join(', ') : '—'
}

function urlOf(channel: Pick<RestChannelView, 'webhookPath'>) {
  return webhookUrl(window.location.origin, channel.webhookPath)
}

function curlOf(channel: RestChannelView) {
  const event = events.value.find((item) => item.key === channelEvents(channel)[0])
  return curlExample(urlOf(channel), sampleBody(event?.publishedFields ?? event?.fields ?? []))
}

function load(channel: RestChannelView | undefined) {
  form.key = channel?.key ?? ''
  form.name = channel?.name ?? ''
  form.description = channel?.description ?? ''
  form.detection = channel?.detection ?? 'FIXED'
  form.detectionValue = channel?.detectionValue ?? publishedEvents.value[0]?.key ?? ''
  form.allowedEventKeys = [...(channel?.allowedEventKeys ?? [])]
  form.userIds = [...(channel?.userIds ?? [])]
  form.groupIds = [...(channel?.groupIds ?? [])]
}

async function loadEvents() {
  try {
    events.value = await eventRegistryApi.events()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function refresh() {
  loading.value = true
  try {
    channels.value = await eventRegistryApi.channels()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function changeDetection(value: KeyDetection) {
  if (form.detection === 'FIXED' && value !== 'FIXED' && form.detectionValue) {
    form.allowedEventKeys = [form.detectionValue]
    form.detectionValue = ''
  } else if (value === 'FIXED') {
    form.detectionValue = form.allowedEventKeys[0] ?? ''
  }
  form.detection = value
}

function openCreate() {
  editingKey.value = null
  load(undefined)
  editorVisible.value = true
}

function openEdit(key: string) {
  const channel = channels.value.find((item) => item.key === key)
  if (!channel) return
  editingKey.value = key
  load(channel)
  editorVisible.value = true
}

async function confirmDiscard() {
  if (props.readOnly) return true
  const untouchedNew = isNew.value && !form.key && !form.name && !form.description
  if (!dirty.value || untouchedNew) return true
  return confirmAction('Kênh có thay đổi chưa lưu. Bỏ các thay đổi này?', 'Bỏ thay đổi', {
    type: 'warning',
    confirmButtonText: 'Bỏ thay đổi',
    cancelButtonText: 'Ở lại',
  })
}

async function closeEditor(done?: () => void) {
  if (!(await confirmDiscard())) return
  editorVisible.value = false
  editingKey.value = null
  done?.()
}

async function save() {
  if (!canSave.value || !form.detection) return
  const key = form.key.trim()
  saving.value = true
  try {
    const saved = await eventRegistryApi.saveChannel(key, {
      revision: selected.value?.revision ?? 0,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      detection: form.detection,
      detectionValue: form.detectionValue.trim(),
      allowedEventKeys: allowedEvents(form),
      userIds: form.userIds.map((value) => value.trim()),
      groupIds: form.groupIds.map((value) => value.trim()),
    })
    await refresh()
    editingKey.value = saved.key
    load(channels.value.find((channel) => channel.key === saved.key) ?? saved)
    ElMessage.success('Đã lưu nháp kênh')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publish(key: string) {
  const channel = channels.value.find((item) => item.key === key)
  if (!channel || !canPublish(channel)) return
  publishingKey.value = key
  try {
    const published = await eventRegistryApi.publishChannel(channel.key, channel.revision)
    await refresh()
    if (editingKey.value === key) load(channels.value.find((item) => item.key === key) ?? published)
    ElMessage.success(`Đã phát hành v${published.publishedVersion ?? ''}`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishingKey.value = ''
  }
}

async function remove(key: string) {
  const confirmed = await confirmAction(
    `Xóa kênh ${key}? Hệ thống bên ngoài sẽ không gửi được vào kênh này nữa.`,
    'Xóa kênh',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  try {
    await eventRegistryApi.deleteChannel(key)
    if (editingKey.value === key) {
      editorVisible.value = false
      editingKey.value = null
    }
    await refresh()
    ElMessage.success('Đã xóa kênh')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function closeWebhook(done?: () => void) {
  webhookKey.value = ''
  done?.()
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Đã sao chép')
  } catch {
    ElMessage.warning('Không sao chép được, hãy chọn và sao chép thủ công')
  }
}

watch(
  () => props.eventsVersion,
  () => {
    void loadEvents()
  },
)

onMounted(() => {
  void loadEvents()
  void refresh()
})
</script>

<template>
  <div class="registry-panel">
    <div class="registry-toolbar">
      <el-button data-action="reload-channels" :loading="loading" @click="refresh">
        <RefreshCw :size="16" />Tải lại
      </el-button>
      <el-button v-if="!readOnly" type="primary" data-action="create-channel" @click="openCreate">
        <Plus :size="16" />Tạo kênh
      </el-button>
    </div>

    <section class="table-panel elevated-panel">
      <el-table v-loading="loading" :data="channels" row-key="key" table-layout="fixed">
        <el-table-column label="STT" width="64" align="center">
          <template #default="{ $index }">
            <span class="registry-index">{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Kênh" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="registry-name-cell" :data-channel="row.key">
              <strong>{{ row.name }}</strong>
              <small>{{ row.key }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Nhận diện" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ detectionLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="Sự kiện nhận" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ channelEvents(row).join(', ') || '—' }}</template>
        </el-table-column>
        <el-table-column label="Được gửi" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ callersLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="Phiên bản" width="100" align="center">
          <template #default="{ row }">{{ row.publishedVersion ? `v${row.publishedVersion}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="Trạng thái" width="170">
          <template #default="{ row }">
            <TableTagCell>
              <el-tag :type="registryStatusTag[registryStatus(row)]" effect="light" round>
                {{ registryStatusLabel[registryStatus(row)] }}
              </el-tag>
            </TableTagCell>
          </template>
        </el-table-column>
        <el-table-column label="Cập nhật" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="Hành động" :width="readOnly ? 190 : 360" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                link
                type="primary"
                data-action="edit-channel"
                :data-channel="row.key"
                @click="openEdit(row.key)"
              >
                <template v-if="readOnly"><Eye :size="14" />Xem</template>
                <template v-else><SquarePen :size="14" />Sửa</template>
              </el-button>
              <el-button
                link
                type="primary"
                data-action="open-webhook"
                :data-channel="row.key"
                @click="webhookKey = row.key"
              >
                <Webhook :size="14" />Webhook
              </el-button>
              <template v-if="!readOnly">
                <el-button
                  link
                  type="success"
                  data-action="publish-channel-row"
                  :data-channel="row.key"
                  :disabled="!canPublish(row) || Boolean(publishingKey)"
                  :loading="publishingKey === row.key"
                  @click="publish(row.key)"
                >
                  <Rocket :size="14" />Phát hành
                </el-button>
                <el-button
                  link
                  type="danger"
                  data-action="delete-channel"
                  :data-channel="row.key"
                  @click="remove(row.key)"
                >
                  <Trash2 :size="14" />Xóa
                </el-button>
              </template>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <ListEmptyState
            title="Chưa có kênh nhận"
            description="Tạo một kênh để hệ thống bên ngoài gửi sự kiện vào qua webhook."
          >
            <el-button v-if="!readOnly" type="primary" @click="openCreate">
              <Plus :size="16" />Tạo kênh
            </el-button>
          </ListEmptyState>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-if="editorVisible"
      :model-value="true"
      :title="isNew ? 'Tạo kênh' : readOnly ? 'Xem kênh' : 'Sửa kênh'"
      width="min(820px, calc(100vw - 32px))"
      :before-close="closeEditor"
    >
      <div class="registry-editor">
        <div v-if="selected" class="registry-badges">
          <el-tag v-if="selected.publishedVersion" type="success">
            Đã phát hành v{{ selected.publishedVersion }}
          </el-tag>
          <el-tag v-if="selected.changed" type="warning">Có thay đổi chưa phát hành</el-tag>
        </div>

        <div class="registry-form">
          <label>
            <span>Mã kênh</span>
            <input
              v-model="form.key"
              data-field="channel-key"
              class="registry-input"
              placeholder="paymentWebhook"
              :disabled="!isNew || readOnly"
            />
            <small v-if="isNew && form.key && !KEY_PATTERN.test(form.key.trim())" class="registry-error">
              Chữ cái đầu, sau đó chữ, số, _ hoặc -
            </small>
          </label>
          <label>
            <span>Tên</span>
            <input
              v-model="form.name"
              data-field="channel-name"
              class="registry-input"
              placeholder="Webhook thanh toán"
              :disabled="readOnly"
            />
          </label>
          <label class="registry-form__wide">
            <span>Mô tả</span>
            <input
              v-model="form.description"
              data-field="channel-description"
              class="registry-input"
              :disabled="readOnly"
            />
          </label>
          <label>
            <span>Nhận diện sự kiện</span>
            <select
              :value="form.detection"
              data-field="detection"
              class="registry-input"
              :disabled="readOnly"
              @change="changeDetection(($event.target as HTMLSelectElement).value as KeyDetection)"
            >
              <option v-for="mode in KEY_DETECTIONS" :key="mode.value" :value="mode.value">
                {{ mode.label }}
              </option>
            </select>
          </label>
          <label v-if="form.detection === 'FIXED'">
            <span>Sự kiện</span>
            <select
              v-model="form.detectionValue"
              data-field="fixed-event"
              class="registry-input"
              :disabled="readOnly"
            >
              <option value="" disabled>Chọn sự kiện đã phát hành</option>
              <option v-for="event in publishedEvents" :key="event.key" :value="event.key">
                {{ event.name }} ({{ event.key }})
              </option>
            </select>
          </label>
          <label v-else>
            <span>{{ form.detection === 'JSON_FIELD' ? 'Tên trường JSON' : 'JSON Pointer' }}</span>
            <input
              v-model="form.detectionValue"
              data-field="detection-value"
              class="registry-input"
              :disabled="readOnly"
              :placeholder="form.detection === 'JSON_FIELD' ? 'eventType' : '/meta/eventType'"
            />
          </label>
        </div>

        <div v-if="form.detection !== 'FIXED'" class="registry-form" data-field="allowed-events">
          <label class="registry-form__wide">
            <span>Sự kiện được nhận</span>
            <el-select
              v-model="form.allowedEventKeys"
              multiple
              :disabled="readOnly"
              placeholder="Chọn sự kiện đã phát hành"
            >
              <el-option
                v-for="event in publishedEvents"
                :key="event.key"
                :label="`${event.name} (${event.key})`"
                :value="event.key"
              />
            </el-select>
          </label>
        </div>

        <div class="registry-form">
          <label>
            <span>Người được gửi</span>
            <el-select
              v-model="form.userIds"
              data-field="user-ids"
              multiple
              filterable
              allow-create
              default-first-option
              :disabled="readOnly"
              :reserve-keyword="false"
              placeholder="Mã người dùng hoặc client, ví dụ erp-client"
            />
          </label>
          <label>
            <span>Vai trò được gửi</span>
            <el-select
              v-model="form.groupIds"
              data-field="group-ids"
              multiple
              filterable
              allow-create
              default-first-option
              :disabled="readOnly"
              :reserve-keyword="false"
              placeholder="Mã vai trò, ví dụ WF_ADMIN"
            />
          </label>
        </div>

        <div
          v-if="!readOnly && problems.length && (isNew ? Boolean(form.key || form.name) : true)"
          class="registry-problems"
          data-list="problems"
          role="alert"
        >
          <strong>Cần sửa trước khi lưu</strong>
          <ul>
            <li v-for="problem in problems" :key="problem">{{ problem }}</li>
          </ul>
        </div>
        <div v-if="unpublishedEvents.length" class="registry-problems" role="alert">
          Sự kiện chưa được phát hành: {{ unpublishedEvents.join(', ') }}. Phát hành sự kiện trước
          khi phát hành kênh.
        </div>

        <div v-if="selected" class="registry-webhook" data-section="webhook">
          <strong>Địa chỉ webhook</strong>
          <div class="registry-webhook__url">
            <code data-field="webhook-url">{{ urlOf(selected) }}</code>
            <el-button size="small" data-action="copy-url" @click="copy(urlOf(selected))">
              Sao chép
            </el-button>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button data-action="close-channel" @click="closeEditor()">
          {{ readOnly ? 'Đóng' : 'Hủy' }}
        </el-button>
        <template v-if="!readOnly">
          <el-button
            data-action="save-channel"
            :disabled="!canSave"
            :loading="saving"
            @click="save"
          >
            Lưu nháp
          </el-button>
          <el-button
            data-action="publish-channel"
            type="primary"
            :disabled="!canPublishInEditor"
            :loading="Boolean(editingKey) && publishingKey === editingKey"
            @click="editingKey && publish(editingKey)"
          >
            Phát hành
          </el-button>
        </template>
      </template>
    </el-dialog>

    <el-dialog
      v-if="webhookChannel"
      :model-value="true"
      :title="`Webhook · ${webhookChannel.name}`"
      width="min(760px, calc(100vw - 32px))"
      :before-close="closeWebhook"
    >
      <div class="registry-webhook" data-section="webhook-details">
        <strong>Địa chỉ webhook</strong>
        <div class="registry-webhook__url">
          <code data-field="webhook-url">{{ urlOf(webhookChannel) }}</code>
          <el-button size="small" data-action="copy-url" @click="copy(urlOf(webhookChannel))">
            Sao chép
          </el-button>
        </div>
        <strong>Ví dụ gọi</strong>
        <pre data-field="curl">{{ curlOf(webhookChannel) }}</pre>
        <div>
          <el-button size="small" data-action="copy-curl" @click="copy(curlOf(webhookChannel))">
            Sao chép lệnh
          </el-button>
        </div>
        <p class="registry-hint">
          Mỗi sự kiện cần một Idempotency-Key dạng UUID. Chỉ dùng lại khóa khi gửi lại đúng sự kiện
          đó.
        </p>
        <p v-if="!webhookChannel.publishedVersion" class="registry-problems" role="alert">
          Kênh chưa được phát hành nên chưa nhận sự kiện.
        </p>
      </div>
      <template #footer>
        <el-button data-action="close-webhook" @click="closeWebhook()">Đóng</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped src="./registry.css"></style>
