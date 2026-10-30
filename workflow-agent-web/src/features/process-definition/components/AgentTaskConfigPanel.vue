<script setup lang="ts">
import { Plus } from '@lucide/vue'
import type { PublishedAgentVersion } from '@/features/agent'

interface MappingRow {
  field: string
  source?: string
  target?: string
}

interface SchemaPathOption {
  path: string
  type: string
  mapping: 'value' | 'array' | 'index' | 'projection'
}

const props = defineProps<{
  agentVersionId: string
  agentVersions: PublishedAgentVersion[]
  selectedAgentVersion?: PublishedAgentVersion
  agentVersionSearchLoading: boolean
  agentInputSchemaFields: SchemaPathOption[]
  agentOutputSchemaFields: SchemaPathOption[]
  agentMappingRows: MappingRow[]
  agentOutputMappingRows: MappingRow[]
  agentProcessWaitTimeoutSeconds: number
  agentProcessFailurePolicy: 'CONTINUE_EMPTY' | 'MANUAL_REVIEW' | 'HOLD_FOR_OPERATIONS'
  searchAgentVersions: (query: string) => void
  schemaFieldLabel: (field: SchemaPathOption) => string
  applyAgentVersion: (value: string) => void
  applyAgentMappings: () => void
  addAgentMappingRow: () => void
  removeAgentMappingRow: (index: number) => void
  addAgentOutputMappingRow: () => void
  removeAgentOutputMappingRow: (index: number) => void
  applyAgentProcessPolicy: () => void
}>()

const emit = defineEmits<{
  'update:agentVersionId': [value: string]
  'update:agentProcessWaitTimeoutSeconds': [value: number]
  'update:agentProcessFailurePolicy': [
    value: 'CONTINUE_EMPTY' | 'MANUAL_REVIEW' | 'HOLD_FOR_OPERATIONS',
  ]
}>()
</script>

<template>
  <div class="property-block agent-task-config">
    <strong>Cấu hình Agent</strong>
    <el-form label-position="top" size="small">
      <el-form-item label="Phiên bản Agent" required>
        <el-select
          :model-value="props.agentVersionId"
          filterable
          remote
          clearable
          :remote-method="props.searchAgentVersions"
          :loading="props.agentVersionSearchLoading"
          placeholder="Chọn phiên bản đã phát hành"
          @update:model-value="emit('update:agentVersionId', $event)"
          @change="props.applyAgentVersion"
        >
          <el-option
            v-for="version in props.agentVersions"
            :key="version.id"
            :label="`${version.agentName} · Phiên bản ${version.version}`"
            :value="String(version.id)"
          />
        </el-select>
      </el-form-item>
      <div v-if="props.selectedAgentVersion" class="agent-version-summary">
        <span>Cách chạy: gọi mô hình</span>
        <span>Giới hạn chạy Agent: {{ props.selectedAgentVersion.timeoutSeconds }} giây</span>
        <span>Dữ liệu vào và ra của phiên bản này</span>
      </div>
      <el-form-item label="Dữ liệu gửi đi">
        <div class="agent-mapping-editor">
          <div class="agent-mapping-editor__toolbar">
            <span>Trường Agent ← dữ liệu quy trình</span>
            <el-button link type="primary" @click="props.addAgentMappingRow">
              <Plus :size="14" />Thêm
            </el-button>
          </div>
          <div
            v-for="(row, index) in props.agentMappingRows"
            :key="index"
            class="agent-mapping-row"
          >
            <el-input
              v-if="!props.agentInputSchemaFields.length"
              v-model="row.field"
              placeholder="Trường Agent, ví dụ customerName"
              @blur="props.applyAgentMappings"
            />
            <el-select
              v-else
              v-model="row.field"
              filterable
              placeholder="Chọn trường đầu vào của Agent"
              @change="props.applyAgentMappings"
            >
              <el-option
                v-for="field in props.agentInputSchemaFields"
                :key="field.path"
                :label="props.schemaFieldLabel(field)"
                :value="field.path"
              />
            </el-select>
            <span>←</span>
            <el-input
              v-model="row.source"
              placeholder="Biến quy trình, ví dụ customer.name"
              @blur="props.applyAgentMappings"
            />
            <el-button link type="danger" @click="props.removeAgentMappingRow(index)">
              Xóa
            </el-button>
          </div>
          <div v-if="!props.agentMappingRows.length" class="agent-mapping-editor__empty">
            Chưa gắn dữ liệu. Nếu không cấu hình, quy trình không tự gửi dữ liệu.
          </div>
        </div>
        <p class="property-hint">
          Bên trái là dữ liệu Agent cần. Bên phải là dữ liệu của quy trình. Chỉ những mục bạn gắn mới được gửi.
        </p>
      </el-form-item>
      <el-form-item label="Dữ liệu nhận về">
        <div class="agent-mapping-editor">
          <div class="agent-mapping-editor__toolbar">
            <span>Kết quả Agent → dữ liệu quy trình</span>
            <el-button link type="primary" @click="props.addAgentOutputMappingRow">
              <Plus :size="14" />Thêm
            </el-button>
          </div>
          <div
            v-for="(row, index) in props.agentOutputMappingRows"
            :key="index"
            class="agent-mapping-row"
          >
            <el-input
              v-if="!props.agentOutputSchemaFields.length"
              v-model="row.field"
              placeholder="Trường đầu ra, ví dụ decision"
              @blur="props.applyAgentMappings"
            />
            <el-select
              v-else
              v-model="row.field"
              filterable
              placeholder="Chọn trường đầu ra của Agent"
              @change="props.applyAgentMappings"
            >
              <el-option
                v-for="field in props.agentOutputSchemaFields"
                :key="field.path"
                :label="props.schemaFieldLabel(field)"
                :value="field.path"
              />
            </el-select>
            <span>→</span>
            <el-input
              v-model="row.target"
              placeholder="Tên dữ liệu mới, ví dụ agentDecision"
              @blur="props.applyAgentMappings"
            />
            <el-button link type="danger" @click="props.removeAgentOutputMappingRow(index)">
              Xóa
            </el-button>
          </div>
          <div v-if="!props.agentOutputMappingRows.length" class="agent-mapping-editor__empty">
            Chưa gắn dữ liệu. Kết quả Agent không tự ghi vào quy trình.
          </div>
        </div>
        <p class="property-hint">
          Có thể lấy một mục, ví dụ items.0, hoặc cả danh sách, ví dụ items.*.name.
        </p>
      </el-form-item>
      <el-form-item label="Thời gian chờ của quy trình (giây)" required>
        <el-input-number
          :model-value="props.agentProcessWaitTimeoutSeconds"
          :min="1"
          :max="props.selectedAgentVersion?.timeoutSeconds || 3600"
          @update:model-value="emit('update:agentProcessWaitTimeoutSeconds', $event || 1)"
          @change="props.applyAgentProcessPolicy"
        />
        <p class="property-hint">Không được dài hơn thời gian chạy của phiên bản Agent.</p>
      </el-form-item>
      <el-form-item label="Khi Agent lỗi" required>
        <el-select
          :model-value="props.agentProcessFailurePolicy"
          @update:model-value="emit('update:agentProcessFailurePolicy', $event)"
          @change="props.applyAgentProcessPolicy"
        >
          <el-option label="Dừng lại, chờ người xử lý" value="HOLD_FOR_OPERATIONS" />
          <el-option label="Đi tiếp, không có kết quả" value="CONTINUE_EMPTY" />
        </el-select>
        <p class="property-hint">
          Nếu Agent lỗi, công việc không bị xóa. Người kiểm tra nhận việc khi Agent chạy xong.
        </p>
      </el-form-item>
      <p class="property-hint">Nhiệm vụ Agent chạy nền. Quy trình đi tiếp khi Agent xong.</p>
    </el-form>
  </div>
</template>
