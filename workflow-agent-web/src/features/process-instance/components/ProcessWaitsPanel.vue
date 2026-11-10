<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import { formatDateTime } from '@/utils/format'
import { processInstanceApi } from '../api'
import type { ProcessWait } from '../types'
import { parseWaitVariables } from '../waitVariables'

const props = defineProps<{
  processInstanceId: string
  tenantCode: string
  canOperate: boolean
}>()
const emit = defineEmits<{ triggered: [] }>()

const ELEMENT_LABELS: Record<NonNullable<ProcessWait['elementType']>, string> = {
  receiveTask: 'Bước chờ',
  intermediateCatchEvent: 'Sự kiện chờ',
  boundaryEvent: 'Sự kiện gắn biên',
}

const waitsQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.processInstanceWaits(props.tenantCode, props.processInstanceId),
  ),
  queryFn: () => processInstanceApi.waits(props.processInstanceId),
})
const waits = computed(() => waitsQuery.data.value ?? [])

const dialogVisible = ref(false)
const selected = ref<ProcessWait>()
const form = reactive({ reason: '', variables: '' })
const formError = ref('')

const triggerMutation = useMutation({
  mutationFn: () => {
    const wait = selected.value
    if (!wait) throw new Error('Chưa chọn bước chờ')
    if (!form.reason.trim()) throw new Error('Hãy nhập lý do')
    return processInstanceApi.triggerReceiveTask({
      processInstanceId: props.processInstanceId,
      activityId: wait.activityId,
      executionId: wait.executionId,
      variables: parseWaitVariables(form.variables),
      reason: form.reason.trim(),
    })
  },
  onSuccess: async () => {
    dialogVisible.value = false
    ElMessage.success('Đã cho bước chờ tiếp tục')
    await waitsQuery.refetch()
    emit('triggered')
  },
  onError: (error) => {
    formError.value = getErrorMessage(error)
  },
})

function open(wait: ProcessWait) {
  selected.value = wait
  Object.assign(form, { reason: '', variables: '' })
  formError.value = ''
  dialogVisible.value = true
}

function submit() {
  formError.value = ''
  triggerMutation.mutate()
}
</script>

<template>
  <div v-loading="waitsQuery.isFetching.value" class="process-waits">
    <p v-if="!waits.length && !waitsQuery.isFetching.value" class="waits-empty">
      Không có bước nào đang chờ
    </p>
    <ul v-else class="wait-list">
      <li
        v-for="wait in waits"
        :key="wait.executionId + wait.activityId"
        class="wait-item"
        :class="wait.kind === 'EVENT' ? 'wait-event' : 'wait-manual'"
      >
        <div class="wait-head">
          <div>
            <strong>{{ wait.activityName || wait.activityId }}</strong>
            <small class="wait-id">{{ wait.activityId }}</small>
          </div>
          <el-button
            v-if="wait.kind === 'MANUAL' && canOperate"
            class="wait-continue"
            link
            type="primary"
            @click="open(wait)"
          >
            Tiếp tục
          </el-button>
        </div>
        <div class="wait-meta">
          <el-tag v-if="wait.kind === 'EVENT'" size="small" type="primary" class="wait-badge">
            Chờ sự kiện <code>{{ wait.eventKey }}</code>
          </el-tag>
          <el-tag v-else size="small" type="info">Chờ tiếp tục thủ công</el-tag>
          <span v-if="wait.kind === 'EVENT' && wait.elementType">
            {{ ELEMENT_LABELS[wait.elementType] }}
          </span>
          <span>Từ {{ formatDateTime(wait.since) }}</span>
          <span v-if="wait.dueDate">Hạn chờ {{ formatDateTime(wait.dueDate) }}</span>
        </div>
        <table v-if="wait.kind === 'EVENT' && wait.correlations?.length" class="wait-correlations">
          <thead>
            <tr>
              <th>Trường tương quan</th>
              <th>Biểu thức</th>
              <th>Giá trị hiện tại</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="correlation in wait.correlations" :key="correlation.name">
              <td>
                <code>{{ correlation.name }}</code>
              </td>
              <td>
                <code>{{ correlation.expression }}</code>
              </td>
              <td>
                <template v-if="correlation.evaluated">{{
                  correlation.currentValue ?? '—'
                }}</template>
                <em v-else class="not-evaluated">không tính lại</em>
              </td>
            </tr>
          </tbody>
        </table>
        <el-alert
          v-if="wait.kind === 'EVENT' && wait.correlationChanged === true"
          class="wait-changed"
          title="Biến đã đổi sau khi bắt đầu chờ. Sự kiện cần mang giá trị cũ để khớp"
          type="warning"
          :closable="false"
          show-icon
        />
      </li>
    </ul>

    <el-dialog v-model="dialogVisible" title="Tiếp tục bước chờ" width="520px">
      <el-form label-position="top">
        <el-form-item label="Bước">
          <span>{{ selected?.activityName || selected?.activityId }}</span>
        </el-form-item>
        <el-form-item label="Lý do" required>
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="Biến gửi kèm (JSON, không bắt buộc)">
          <el-input
            v-model="form.variables"
            type="textarea"
            :rows="4"
            placeholder='{"approved": true}'
          />
        </el-form-item>
      </el-form>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <template #footer>
        <el-button @click="dialogVisible = false">Hủy</el-button>
        <el-button type="primary" :loading="triggerMutation.isPending.value" @click="submit">
          Tiếp tục
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.process-waits {
  min-height: 120px;
}

.waits-empty {
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 32px 0;
}

.wait-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.wait-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
}

.wait-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.wait-id {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

.wait-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-top: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.wait-correlations {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 13px;
}

.wait-correlations th,
.wait-correlations td {
  padding: 4px 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  text-align: left;
}

.not-evaluated {
  color: var(--el-text-color-secondary);
}

.wait-changed {
  margin-top: 8px;
}
</style>
