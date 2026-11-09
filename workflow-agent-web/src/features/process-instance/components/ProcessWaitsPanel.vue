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
  <div v-loading="waitsQuery.isFetching.value">
    <el-table :data="waits" height="470" empty-text="Không có bước chờ thủ công nào">
      <el-table-column label="Bước" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.activityName || row.activityId }}</template>
      </el-table-column>
      <el-table-column prop="activityId" label="Mã bước" min-width="150" />
      <el-table-column label="Đang chờ từ" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.since) }}</template>
      </el-table-column>
      <el-table-column v-if="canOperate" label="Thao tác" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">Tiếp tục</el-button>
        </template>
      </el-table-column>
    </el-table>

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
