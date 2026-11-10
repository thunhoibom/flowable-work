<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Eye, Plus, RefreshCw, Rocket, SquarePen, Trash2 } from '@lucide/vue'
import { getErrorMessage } from '@/api/http'
import ListEmptyState from '@/components/ListEmptyState.vue'
import TableTagCell from '@/components/TableTagCell.vue'
import { confirmAction } from '@/utils/confirmation'
import { formatDateTime } from '@/utils/format'
import {
  eventRegistryApi,
  type EventFieldView,
  type EventModelView,
  type EventUsagesView,
} from './api'
import {
  FIELD_TYPES,
  KEY_PATTERN,
  blankField,
  incompatibleChanges,
  sameFields,
  validateEventModel,
} from './eventModelDraft'
import { registryStatus, registryStatusLabel, registryStatusTag } from './registryStatus'

const props = withDefaults(
  defineProps<{
    /** Viewers and operators: no create, save, publish or delete. */
    readOnly?: boolean
  }>(),
  { readOnly: false },
)
const emit = defineEmits<{ changed: [] }>()

const events = ref<EventModelView[]>([])
const loading = ref(false)
const saving = ref(false)
/** Key of the event being published, so only that row shows a spinner. */
const publishingKey = ref('')
const editorVisible = ref(false)
/** Key of the event open in the modal. Null while creating a new one. */
const editingKey = ref<string | null>(null)
const form = reactive<{ key: string; name: string; description: string; fields: EventFieldView[] }>({
  key: '',
  name: '',
  description: '',
  fields: [],
})
const usages = ref<EventUsagesView | null>(null)
let usageSequence = 0

const selected = computed(() =>
  editingKey.value === null ? undefined : events.value.find((event) => event.key === editingKey.value),
)
const isNew = computed(() => editingKey.value === null)
const keyTaken = computed(
  () => isNew.value && events.value.some((event) => event.key === form.key.trim()),
)
const problems = computed(() => {
  const list = validateEventModel({ key: form.key.trim(), name: form.name, fields: form.fields })
  if (keyTaken.value) list.unshift('Sự kiện đã tồn tại')
  return list
})
const incompatibilities = computed(() =>
  selected.value?.publishedFields
    ? incompatibleChanges(selected.value.publishedFields, form.fields)
    : [],
)
const dirty = computed(() => {
  if (isNew.value) return true
  const current = selected.value
  if (!current) return false
  return (
    form.name !== current.name ||
    form.description !== (current.description ?? '') ||
    !sameFields(form.fields, current.fields)
  )
})
const canSave = computed(
  () => !props.readOnly && !problems.value.length && dirty.value && !saving.value,
)
const canPublishInEditor = computed(
  () =>
    !isNew.value &&
    !dirty.value &&
    !incompatibilities.value.length &&
    Boolean(selected.value && canPublish(selected.value)),
)
/** R-E4: a published event in use should only gain fields. */
const usedWhileDirty = computed(
  () => !isNew.value && Boolean(usages.value?.usages.length) && dirty.value,
)

function canPublish(event: EventModelView) {
  if (!event.changed) return false
  return !(event.publishedFields && incompatibleChanges(event.publishedFields, event.fields).length)
}

function correlationNames(event: EventModelView) {
  return event.fields.filter((field) => field.correlation).map((field) => field.name)
}

async function loadUsages(event: EventModelView | undefined) {
  const sequence = ++usageSequence
  usages.value = null
  if (!event?.publishedVersion) return
  try {
    const result = await eventRegistryApi.usages(event.key)
    if (sequence === usageSequence) usages.value = result
  } catch {
    if (sequence === usageSequence) usages.value = null
  }
}

function load(model: EventModelView | undefined) {
  form.key = model?.key ?? ''
  form.name = model?.name ?? ''
  form.description = model?.description ?? ''
  form.fields = (model?.fields ?? [{ ...blankField(), correlation: true }]).map((field) => ({
    ...field,
  }))
}

async function refresh() {
  loading.value = true
  try {
    events.value = await eventRegistryApi.events()
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingKey.value = null
  load(undefined)
  usages.value = null
  editorVisible.value = true
}

function openEdit(key: string) {
  const event = events.value.find((item) => item.key === key)
  if (!event) return
  editingKey.value = key
  load(event)
  editorVisible.value = true
  void loadUsages(event)
}

async function confirmDiscard() {
  if (props.readOnly) return true
  const untouchedNew =
    isNew.value && !form.key && !form.name && !form.description && form.fields.every((f) => !f.name)
  if (!dirty.value || untouchedNew) return true
  return confirmAction('Sự kiện có thay đổi chưa lưu. Bỏ các thay đổi này?', 'Bỏ thay đổi', {
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

function addField() {
  form.fields.push(blankField())
}

function removeField(index: number) {
  form.fields.splice(index, 1)
}

async function save() {
  if (!canSave.value) return
  const key = form.key.trim()
  saving.value = true
  try {
    const saved = await eventRegistryApi.saveEvent(key, {
      revision: selected.value?.revision ?? 0,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      fields: form.fields.map((field) => ({ ...field, name: field.name.trim() })),
    })
    await refresh()
    editingKey.value = saved.key
    load(events.value.find((event) => event.key === saved.key) ?? saved)
    void loadUsages(selected.value)
    ElMessage.success('Đã lưu nháp sự kiện')
    emit('changed')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function publish(key: string) {
  const event = events.value.find((item) => item.key === key)
  if (!event || !canPublish(event)) return
  publishingKey.value = key
  try {
    const published = await eventRegistryApi.publishEvent(event.key, event.revision)
    await refresh()
    if (editingKey.value === key) {
      load(events.value.find((item) => item.key === key) ?? published)
      void loadUsages(selected.value)
    }
    ElMessage.success(`Đã phát hành v${published.publishedVersion ?? ''}`)
    emit('changed')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    publishingKey.value = ''
  }
}

async function remove(key: string) {
  const confirmed = await confirmAction(
    `Xóa sự kiện ${key}? Bản đã phát hành cũng bị gỡ.`,
    'Xóa sự kiện',
    { type: 'warning', confirmButtonText: 'Xóa', cancelButtonText: 'Hủy' },
  )
  if (!confirmed) return
  try {
    await eventRegistryApi.deleteEvent(key)
    if (editingKey.value === key) {
      editorVisible.value = false
      editingKey.value = null
    }
    await refresh()
    ElMessage.success('Đã xóa sự kiện')
    emit('changed')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="registry-panel">
    <div class="registry-toolbar">
      <el-button data-action="reload-events" :loading="loading" @click="refresh">
        <RefreshCw :size="16" />Tải lại
      </el-button>
      <el-button v-if="!readOnly" type="primary" data-action="create-event" @click="openCreate">
        <Plus :size="16" />Tạo sự kiện
      </el-button>
    </div>

    <section class="table-panel elevated-panel">
      <el-table v-loading="loading" :data="events" row-key="key" table-layout="fixed">
        <el-table-column label="STT" width="64" align="center">
          <template #default="{ $index }">
            <span class="registry-index">{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Sự kiện" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="registry-name-cell" :data-event="row.key">
              <strong>{{ row.name }}</strong>
              <small>{{ row.key }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Mô tả" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="Số trường" width="100" align="center">
          <template #default="{ row }">{{ row.fields.length }}</template>
        </el-table-column>
        <el-table-column label="Trường tương quan" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ correlationNames(row).join(', ') || '—' }}</template>
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
        <el-table-column label="Hành động" :width="readOnly ? 100 : 270" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                link
                type="primary"
                data-action="edit-event"
                :data-event="row.key"
                @click="openEdit(row.key)"
              >
                <template v-if="readOnly"><Eye :size="14" />Xem</template>
                <template v-else><SquarePen :size="14" />Sửa</template>
              </el-button>
              <template v-if="!readOnly">
                <el-button
                  link
                  type="success"
                  data-action="publish-event-row"
                  :data-event="row.key"
                  :disabled="!canPublish(row) || Boolean(publishingKey)"
                  :loading="publishingKey === row.key"
                  @click="publish(row.key)"
                >
                  <Rocket :size="14" />Phát hành
                </el-button>
                <el-button
                  link
                  type="danger"
                  data-action="delete-event"
                  :data-event="row.key"
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
            title="Chưa có sự kiện"
            description="Tạo một sự kiện để khai báo dữ liệu hệ thống bên ngoài gửi vào."
          >
            <el-button v-if="!readOnly" type="primary" @click="openCreate">
              <Plus :size="16" />Tạo sự kiện
            </el-button>
          </ListEmptyState>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-if="editorVisible"
      :model-value="true"
      :title="isNew ? 'Tạo sự kiện' : readOnly ? 'Xem sự kiện' : 'Sửa sự kiện'"
      width="min(880px, calc(100vw - 32px))"
      :before-close="closeEditor"
    >
      <div class="registry-editor">
        <div v-if="selected" class="registry-badges">
          <el-tag v-if="selected.publishedVersion" type="success" data-badge="published">
            Đã phát hành v{{ selected.publishedVersion }}
          </el-tag>
          <el-tag v-if="selected.changed" type="warning" data-badge="changed">
            Có thay đổi chưa phát hành
          </el-tag>
        </div>

        <div class="registry-form">
          <label>
            <span>Mã sự kiện</span>
            <input
              v-model="form.key"
              data-field="event-key"
              class="registry-input"
              placeholder="paymentReceived"
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
              data-field="event-name"
              class="registry-input"
              placeholder="Đã nhận thanh toán"
              :disabled="readOnly"
            />
          </label>
          <label class="registry-form__wide">
            <span>Mô tả</span>
            <input
              v-model="form.description"
              data-field="event-description"
              class="registry-input"
              :disabled="readOnly"
            />
          </label>
        </div>

        <div class="registry-table-wrap">
          <table class="registry-table">
            <thead>
              <tr>
                <th class="registry-table__index">STT</th>
                <th>Tên trường</th>
                <th>Kiểu</th>
                <th>Tương quan</th>
                <th>Lấy từ header</th>
                <th>Toàn bộ nội dung</th>
                <th v-if="!readOnly"><span class="visually-hidden">Xóa</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(field, index) in form.fields" :key="index" data-row="field">
                <td class="registry-table__index">{{ index + 1 }}</td>
                <td>
                  <input
                    v-model="field.name"
                    data-field="field-name"
                    class="registry-input"
                    aria-label="Tên trường"
                    :disabled="readOnly"
                  />
                </td>
                <td>
                  <select
                    v-model="field.type"
                    data-field="field-type"
                    class="registry-input"
                    aria-label="Kiểu"
                    :disabled="readOnly"
                  >
                    <option v-for="type in FIELD_TYPES" :key="type.value" :value="type.value">
                      {{ type.label }}
                    </option>
                  </select>
                </td>
                <td>
                  <input
                    v-model="field.correlation"
                    type="checkbox"
                    data-field="field-correlation"
                    aria-label="Tương quan"
                    :disabled="readOnly"
                  />
                </td>
                <td>
                  <input
                    v-model="field.header"
                    type="checkbox"
                    data-field="field-header"
                    aria-label="Lấy từ header"
                    :disabled="readOnly"
                  />
                </td>
                <td>
                  <input
                    v-model="field.fullPayload"
                    type="checkbox"
                    data-field="field-full-payload"
                    aria-label="Toàn bộ nội dung"
                    :disabled="readOnly"
                  />
                </td>
                <td v-if="!readOnly">
                  <el-button link type="danger" data-action="remove-field" @click="removeField(index)">
                    Xóa
                  </el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!readOnly">
          <el-button data-action="add-field" @click="addField"><Plus :size="15" />Thêm trường</el-button>
        </div>
        <p class="registry-hint">
          Trường tương quan dùng để tìm đúng công việc đang chờ, ví dụ mã đơn hàng. Cần 1 đến 3
          trường.
        </p>

        <div v-if="usages" class="registry-usages" data-block="usages">
          <strong>Đang được dùng ở</strong>
          <ul v-if="usages.usages.length">
            <li
              v-for="usage in usages.usages"
              :key="`${usage.processDefinitionKey}:${usage.version}`"
              data-row="usage"
            >
              {{ usage.processDefinitionKey }} v{{ usage.version }} ·
              {{ usage.activityIds.join(', ') }}
            </li>
          </ul>
          <p v-else class="registry-hint">Chưa có quy trình nào chờ sự kiện này.</p>
          <p class="registry-hint" data-field="waiting">{{ usages.waiting }} công việc đang chờ</p>
          <p v-if="usedWhileDirty" class="registry-problems" data-warning="in-use" role="alert">
            Sự kiện đang được quy trình dùng. Chỉ thêm trường mới; thay đổi khác cần mã sự kiện mới
          </p>
        </div>

        <div
          v-if="!readOnly && problems.length && (isNew ? Boolean(form.key || form.name) : dirty)"
          class="registry-problems"
          data-list="problems"
          role="alert"
        >
          <strong>Cần sửa trước khi lưu</strong>
          <ul>
            <li v-for="problem in problems" :key="problem">{{ problem }}</li>
          </ul>
        </div>
        <div
          v-if="incompatibilities.length"
          class="registry-problems"
          data-list="incompatibilities"
          role="alert"
        >
          <strong>Thay đổi không tương thích với bản đã phát hành</strong>
          <ul>
            <li v-for="problem in incompatibilities" :key="problem">{{ problem }}</li>
          </ul>
          <p>Hãy tạo mã sự kiện mới.</p>
        </div>
      </div>

      <template #footer>
        <el-button data-action="close-event" @click="closeEditor()">
          {{ readOnly ? 'Đóng' : 'Hủy' }}
        </el-button>
        <template v-if="!readOnly">
          <el-button
            data-action="save-event"
            :disabled="!canSave"
            :loading="saving"
            @click="save"
          >
            Lưu nháp
          </el-button>
          <el-button
            data-action="publish-event"
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
  </div>
</template>

<style scoped src="./registry.css"></style>
