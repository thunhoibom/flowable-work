export type AgentRecoveryAction =
  | 'RETRY_PROVIDER'
  | 'REPAIR_TOOL_CALL'
  | 'REPAIR_OUTPUT'
  | 'WAIT_FOR_REVIEW'
  | 'CONFIGURATION'
  | 'NONE'

export interface AgentErrorPresentation {
  code: string
  title: string
  description: string
  action: AgentRecoveryAction
  actionLabel: string
}

const catalog: Record<string, Omit<AgentErrorPresentation, 'code'>> = {
  AGENT_OUTPUT_NOT_JSON: {
    title: 'Kết quả không phải JSON hợp lệ',
    description: 'Hệ thống đã thử sửa một lần. Nếu vẫn lỗi, hãy kiểm tra các trường kết quả hoặc xử lý tay.',
    action: 'REPAIR_OUTPUT',
    actionLabel: 'Đã sửa kết quả một lần',
  },
  AGENT_OUTPUT_SCHEMA_INVALID: {
    title: 'Kết quả không đúng mẫu',
    description: 'Agent có trả về JSON, nhưng thiếu trường hoặc sai kiểu so với phiên bản đang dùng.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần kiểm tra các trường kết quả',
  },
  AGENT_INPUT_NOT_JSON: {
    title: 'Dữ liệu vào không phải JSON hợp lệ',
    description: 'Agent này cần dữ liệu vào đúng dạng. Hãy kiểm tra dữ liệu quy trình gửi sang.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần sửa dữ liệu vào',
  },
  AGENT_INPUT_SCHEMA_INVALID: {
    title: 'Dữ liệu vào không đúng mẫu',
    description: 'Dữ liệu từ quy trình thiếu trường bắt buộc, hoặc sai kiểu so với mẫu của Agent.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần sửa dữ liệu gửi đi',
  },
  AGENT_TOOL_CALL_INVALID: {
    title: 'Không gọi được công cụ',
    description: 'Agent không tạo được lời gọi dùng được. Hệ thống dừng để tránh thử lại mãi.',
    action: 'REPAIR_TOOL_CALL',
    actionLabel: 'Đã sửa một lần',
  },
  AGENT_RESULT_EMPTY: {
    title: 'Agent không tạo kết quả',
    description: 'Lượt gọi đã xong nhưng không có nội dung dùng được. Hãy kiểm tra nội dung hướng dẫn và mô hình.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần kiểm tra cách lấy kết quả',
  },
  PROVIDER_UNAVAILABLE: {
    title: 'Nhà cung cấp tạm thời không dùng được',
    description: 'Đây là lỗi tạm. Hệ thống sẽ thử lại theo số lần đã cấu hình.',
    action: 'RETRY_PROVIDER',
    actionLabel: 'Có thể thử lại',
  },
  PROVIDER_TIMEOUT: {
    title: 'Nhà cung cấp không trả lời kịp',
    description: 'Yêu cầu vượt quá thời gian chờ của phiên bản Agent. Hệ thống không gọi lại mãi.',
    action: 'RETRY_PROVIDER',
    actionLabel: 'Có thể thử lại',
  },
  AGENT_CONFIGURATION_ERROR: {
    title: 'Cấu hình Agent chưa dùng được',
    description: 'Phiên bản đã phát hành thiếu nhà cung cấp, khóa hoặc cách chạy. Người quản trị cần kiểm tra.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần người quản trị xem',
  },
  AGENT_EXECUTION_ERROR: {
    title: 'Agent gặp lỗi',
    description: 'Lần chạy gặp lỗi chưa rõ nguyên nhân. Hãy giữ mã lần chạy và Trace ID để người quản trị kiểm tra.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần người quản trị xem',
  },
}

export function getAgentErrorPresentation(code?: string | null): AgentErrorPresentation | null {
  if (!code) return null
  return {
    code,
    ...(catalog[code] || {
      title: 'Agent gặp lỗi chưa rõ',
      description: 'Hệ thống đã ghi nhận lỗi này nhưng chưa có cách xử lý cụ thể hơn. Hãy liên hệ người quản trị.',
      action: 'WAIT_FOR_REVIEW',
      actionLabel: 'Cần người quản trị xem',
    }),
  }
}
