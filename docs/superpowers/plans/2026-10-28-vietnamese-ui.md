# Vietnamese UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Giao diện `workflow-agent-web` chỉ còn tiếng Việt, ngày giờ dạng `DD-MM-YYYY HH:mm:ss`, không thêm thư viện đa ngôn ngữ.

**Architecture:** Thay chuỗi tiếng Trung ngay trong file Vue và TypeScript. Hàm định dạng, nhãn trạng thái, lỗi validate và catalog lỗi Agent là nguồn chữ dùng chung. Màn hình lấy chữ từ đó hoặc từ chuỗi viết trực tiếp. `message` của API đi thẳng ra giao diện.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Playwright, dayjs.

## Global Constraints

- Chỉ sửa `workflow-agent-web`. Không sửa backend.
- Không thêm `vue-i18n` hay file từ điển.
- Không dịch `message` API trả về. Câu dự phòng do giao diện tự viết thì dịch.
- Dữ liệu mẫu trong mock và test giữ nguyên tiếng Trung. Chỉ đổi assertion của chữ giao diện.
- Chữ có sẵn của bpmn-js, nếu đang là tiếng Anh, giữ nguyên. Nhãn do ứng dụng đặt thì dịch.
- Comment trong code không dịch.
- `splitValues` vẫn tách được dấu phẩy Trung `，`.
- Mã quyền, tên trường API, loại phần tử BPMN (`bpmn:UserTask`) và từ `Agent` giữ tiếng Anh.
- Tên trường chèn vào câu lỗi giữ trong dấu “ ”.
- `index.html` dùng `lang="vi"`. Thẻ `<title>` giữ `Workflow Agent`.
- `App.vue` import `element-plus/es/locale/lang/vi`.
- `formatDateTime` dùng `DD-MM-YYYY HH:mm:ss`. Giá trị rỗng là `-`.
- `formatVersion(n)` là `Phiên bản n`.
- Thời lượng: `N giây`, `N phút S giây`, `N giờ M phút`.
- `localeCompare` dùng `'vi'`.
- Mỗi commit chỉ `git add` đúng file của task. Không stage phần service-registry hay file khác đang có sẵn trong working tree.

Từ vựng bắt buộc:

| Tiếng Trung | Tiếng Việt |
| --- | --- |
| 登录 | Đăng nhập |
| 工作台 | Bàn làm việc |
| 流程定义 | Định nghĩa quy trình |
| 流程设计器 | Thiết kế quy trình |
| 流程实例 | Phiên chạy |
| 流程实例详情 | Chi tiết phiên chạy |
| 服务目录 | Danh mục dịch vụ |
| 派单规则 | Quy tắc phân công |
| Agent 中心 | Trung tâm Agent |
| 成员与角色 | Thành viên và vai trò |
| 租户 | Tenant |
| 租户管理 | Quản lý tenant |
| 第 N 版 | Phiên bản N |
| 启用 | Đang bật |
| 停用 | Đã tắt |
| 草稿 | Bản nháp |
| 已发布 | Đã phát hành |
| 成功 | Thành công |
| 失败 | Thất bại |
| 已完成 | Đã hoàn thành |
| 已取消 | Đã hủy |
| 已超时 | Hết thời gian |
| 排队中 | Đang xếp hàng |
| 待执行 | Chờ thực thi |
| 待处理 | Chờ xử lý |
| 运行中 | Đang chạy |
| 已跳过 | Đã bỏ qua |
| 空结果 | Không có kết quả |
| 部分完成 | Hoàn thành một phần |
| 已拒绝 | Đã từ chối |
| 等待人工 | Chờ người xử lý |
| 已安排重试 | Đã lên lịch thử lại |
| 已决策 | Đã quyết định |
| 未知 | Không rõ |
| 新建 | Tạo |
| 发起 | Khởi chạy |
| 保存 | Lưu |
| 发布 | Phát hành |
| 删除 | Xóa |
| 取消 | Hủy |
| 确认 | Xác nhận |
| 编辑 | Sửa |
| 搜索 | Tìm kiếm |

Chữ giao diện mà e2e đang bấm, dùng đúng các câu này:

| Tiếng Trung | Tiếng Việt |
| --- | --- |
| 发布状态 | Trạng thái phát hành |
| 收起导航 | Thu gọn menu |
| 展开导航 | Mở rộng menu |
| 新建流程 | Tạo quy trình |
| 发起流程 | Khởi chạy quy trình |
| 业务标识 | Mã nghiệp vụ |
| 同意 | Đồng ý |
| 同意任务 | Đồng ý nhiệm vụ |
| 退回任务 | Trả lại nhiệm vụ |
| 选择参与人 | Chọn người tham gia |
| 确认选择 | Xác nhận lựa chọn |
| 确认同意 | Xác nhận đồng ý |
| 确认退回 | Xác nhận trả lại |
| 任务已同意 | Đã đồng ý nhiệm vụ |
| 任务已退回 | Đã trả lại nhiệm vụ |
| 流程跟踪 | Theo dõi quy trình |
| 流程标识 | Mã quy trình |
| 流程名称 | Tên quy trình |
| 创建并设计 | Tạo và thiết kế |
| 流程概览 | Tổng quan quy trình |
| 版本管理 | Quản lý phiên bản |
| 元素配置 | Cấu hình phần tử |
| 新建流程图 | Tạo sơ đồ mới |
| 保存新版本 | Lưu phiên bản mới |
| 创建用户任务 | Tạo nhiệm vụ người dùng |
| 用户任务 | Nhiệm vụ người dùng |
| 添加变量 | Thêm biến |
| 输入文本 | Nhập văn bản |
| 参与人设置 | Thiết lập người tham gia |
| 确认发起 | Xác nhận khởi chạy |
| Agent 业务输入 | Dữ liệu nghiệp vụ cho Agent |
| Agent 定义 | Định nghĩa Agent |
| 新增规则 | Thêm quy tắc |
| 可选指定 | Có thể chỉ định |
| 未指定，将使用派单规则 | Chưa chỉ định, sẽ dùng quy tắc phân công |
| 页面不存在 | Không tìm thấy trang |
| 返回流程定义 | Về định nghĩa quy trình |
| 分页 | Phân trang |
| 每页条数 | Số dòng mỗi trang |

Câu thông báo Agent không khai báo dữ liệu:

`Nút Agent sắp tới không khai báo dữ liệu nghiệp vụ cần người điền. Quy trình sẽ chạy theo cấu hình đã phát hành.`

Tiêu đề hộp chọn người: `` Chọn người tham gia cho “${activityName}” ``

---

### Task 1: Ngày, phiên bản, thời lượng

**Files:**
- Modify: `workflow-agent-web/src/utils/format.ts`
- Test: `workflow-agent-web/src/__tests__/format.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `formatDateTime(value?: string | null): string`, `formatVersion(value?: number | null): string`, `formatDuration(value?: number | null): string`. `splitValues` vẫn nhận dấu `，`.

- [ ] **Step 1: Write the failing test**

Thay nội dung `describe` trong `format.spec.ts` bằng:

```ts
describe('format utilities', () => {
  it('normalizes comma and line separated targets', () => {
    expect(splitValues('alice, bob\nops，reviewer')).toEqual(['alice', 'bob', 'ops', 'reviewer'])
    expect(joinValues(['alice', 'bob'])).toBe('alice, bob')
  })

  it('formats dates, versions, durations, and structured variables', () => {
    expect(formatDateTime('2026-10-28T15:04:05')).toBe('28-10-2026 15:04:05')
    expect(formatDateTime(null)).toBe('-')
    expect(formatVersion(2)).toBe('Phiên bản 2')
    expect(formatVersion(null)).toBe('-')
    expect(formatDuration(5_000)).toBe('5 giây')
    expect(formatDuration(90_000)).toBe('1 phút 30 giây')
    expect(formatDuration(3_661_000)).toBe('1 giờ 1 phút')
    expect(formatDuration(null)).toBe('-')
    expect(displayValue({ approved: true })).toBe('{"approved":true}')
  })
})
```

Thêm `formatDateTime` và `formatVersion` vào dòng import từ `@/utils/format`.

- [ ] **Step 2: Run test to verify it fails**

Run từ `workflow-agent-web`: `pnpm exec vitest --run src/__tests__/format.spec.ts`

Expected: FAIL vì `formatDateTime` chưa được import trong test, hoặc kết quả vẫn là `2026-10-28 15:04:05` và `1 小时 1 分`.

- [ ] **Step 3: Write minimal implementation**

Trong `format.ts`, đổi ba hàm thành:

```ts
export function formatDateTime(value?: string | null) {
  return value ? dayjs(value).format('DD-MM-YYYY HH:mm:ss') : '-'
}

export function formatVersion(value?: number | null) {
  return value === null || value === undefined ? '-' : `Phiên bản ${value}`
}

export function formatDuration(value?: number | null) {
  if (value === null || value === undefined) return '-'
  const seconds = Math.floor(value / 1000)
  if (seconds < 60) return `${seconds} giây`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} phút ${seconds % 60} giây`
  const hours = Math.floor(minutes / 60)
  return `${hours} giờ ${minutes % 60} phút`
}
```

Giữ nguyên `splitValues`, kể cả ký tự `，` trong regex.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/format.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/utils/format.ts workflow-agent-web/src/__tests__/format.spec.ts
git commit -m "feat: show dates and durations in Vietnamese"
```

---

### Task 2: Nhãn trạng thái

**Files:**
- Modify: `workflow-agent-web/src/utils/status.ts`
- Test: `workflow-agent-web/src/__tests__/sharedPresentation.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `getStatusPresentation(status: string | boolean): { label: string, tone: StatusTone }`. Nhãn đúng bảng từ vựng. Trạng thái lạ giữ nguyên mã. Chuỗi rỗng trả `Không rõ`.

- [ ] **Step 1: Write the failing test**

Đổi test đầu tiên trong `sharedPresentation.spec.ts` thành:

```ts
it('maps technical lifecycle states to Vietnamese labels', () => {
  expect(getStatusPresentation('RUNNING')).toEqual({ label: 'Đang chạy', tone: 'primary' })
  expect(getStatusPresentation('FAILED')).toEqual({ label: 'Thất bại', tone: 'danger' })
  expect(getStatusPresentation(true)).toEqual({ label: 'Đang bật', tone: 'success' })
  expect(getStatusPresentation(false)).toEqual({ label: 'Đã tắt', tone: 'info' })
  expect(getStatusPresentation('DRAFT')).toEqual({ label: 'Bản nháp', tone: 'warning' })
  expect(getStatusPresentation('PUBLISHED')).toEqual({ label: 'Đã phát hành', tone: 'success' })
  expect(getStatusPresentation('QUEUED')).toEqual({ label: 'Đang xếp hàng', tone: 'primary' })
  expect(getStatusPresentation('PENDING')).toEqual({ label: 'Chờ thực thi', tone: 'info' })
  expect(getStatusPresentation('ACTIVE')).toEqual({ label: 'Chờ xử lý', tone: 'primary' })
  expect(getStatusPresentation('SUCCEEDED')).toEqual({ label: 'Thành công', tone: 'success' })
  expect(getStatusPresentation('SUCCESS')).toEqual({ label: 'Thành công', tone: 'success' })
  expect(getStatusPresentation('COMPLETED')).toEqual({ label: 'Đã hoàn thành', tone: 'success' })
  expect(getStatusPresentation('TIMED_OUT')).toEqual({ label: 'Hết thời gian', tone: 'danger' })
  expect(getStatusPresentation('CANCELLED')).toEqual({ label: 'Đã hủy', tone: 'info' })
  expect(getStatusPresentation('SKIPPED')).toEqual({ label: 'Đã bỏ qua', tone: 'info' })
  expect(getStatusPresentation('EMPTY')).toEqual({ label: 'Không có kết quả', tone: 'warning' })
  expect(getStatusPresentation('PARTIAL')).toEqual({ label: 'Hoàn thành một phần', tone: 'warning' })
  expect(getStatusPresentation('REJECTED')).toEqual({ label: 'Đã từ chối', tone: 'danger' })
  expect(getStatusPresentation('WAITING_FOR_REVIEW')).toEqual({ label: 'Chờ người xử lý', tone: 'warning' })
  expect(getStatusPresentation('RETRYING')).toEqual({ label: 'Đã lên lịch thử lại', tone: 'primary' })
  expect(getStatusPresentation('DECIDED')).toEqual({ label: 'Đã quyết định', tone: 'success' })
  expect(getStatusPresentation('NO_SUCH')).toEqual({ label: 'NO_SUCH', tone: 'info' })
  expect(getStatusPresentation('')).toEqual({ label: 'Không rõ', tone: 'info' })
})
```

Đổi assertion badge `成功` thành `Thành công`. Giữ nguyên test `ListEmptyState`: props tiếng Trung đó là dữ liệu truyền vào, không phải chữ của component.

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/sharedPresentation.spec.ts`

Expected: FAIL vì label vẫn là `运行中`.

- [ ] **Step 3: Write minimal implementation**

Thay `STATUS_PRESENTATIONS` và nhánh fallback trong `status.ts`:

```ts
const STATUS_PRESENTATIONS: Record<string, StatusPresentation> = {
  ENABLED: { label: 'Đang bật', tone: 'success' },
  DISABLED: { label: 'Đã tắt', tone: 'info' },
  DRAFT: { label: 'Bản nháp', tone: 'warning' },
  PUBLISHED: { label: 'Đã phát hành', tone: 'success' },
  QUEUED: { label: 'Đang xếp hàng', tone: 'primary' },
  PENDING: { label: 'Chờ thực thi', tone: 'info' },
  ACTIVE: { label: 'Chờ xử lý', tone: 'primary' },
  RUNNING: { label: 'Đang chạy', tone: 'primary' },
  SUCCEEDED: { label: 'Thành công', tone: 'success' },
  SUCCESS: { label: 'Thành công', tone: 'success' },
  COMPLETED: { label: 'Đã hoàn thành', tone: 'success' },
  FAILED: { label: 'Thất bại', tone: 'danger' },
  TIMED_OUT: { label: 'Hết thời gian', tone: 'danger' },
  CANCELLED: { label: 'Đã hủy', tone: 'info' },
  SKIPPED: { label: 'Đã bỏ qua', tone: 'info' },
  EMPTY: { label: 'Không có kết quả', tone: 'warning' },
  PARTIAL: { label: 'Hoàn thành một phần', tone: 'warning' },
  REJECTED: { label: 'Đã từ chối', tone: 'danger' },
  WAITING_FOR_REVIEW: { label: 'Chờ người xử lý', tone: 'warning' },
  RETRYING: { label: 'Đã lên lịch thử lại', tone: 'primary' },
  DECIDED: { label: 'Đã quyết định', tone: 'success' },
}

export function getStatusPresentation(status: string | boolean): StatusPresentation {
  const normalized = typeof status === 'boolean' ? (status ? 'ENABLED' : 'DISABLED') : status
  return STATUS_PRESENTATIONS[normalized] ?? { label: normalized || 'Không rõ', tone: 'info' }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/sharedPresentation.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/utils/status.ts workflow-agent-web/src/__tests__/sharedPresentation.spec.ts
git commit -m "feat: show workflow status labels in Vietnamese"
```

---

### Task 3: Lỗi mã quy trình

**Files:**
- Modify: `workflow-agent-web/src/features/process-definition/domain.ts`
- Test: `workflow-agent-web/src/__tests__/processDefinitionDomain.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `normalizeProcessIdentity(key: string, name: string): { key: string, name: string }`. Tên nghiệp vụ truyền vào không bị dịch.

- [ ] **Step 1: Write the failing test**

Đổi assertion lỗi thành:

```ts
expect(() => normalizeProcessIdentity(key, '审批')).toThrow(
  'Mã quy trình phải bắt đầu bằng chữ cái',
)
```

Giữ `费用审批` trong test dữ liệu hợp lệ.

Thêm một test:

```ts
it('rejects an empty identity', () => {
  expect(() => normalizeProcessIdentity('  ', '  ')).toThrow(
    'Mã quy trình và tên quy trình không được để trống',
  )
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/processDefinitionDomain.spec.ts`

Expected: FAIL vì message vẫn là tiếng Trung.

- [ ] **Step 3: Write minimal implementation**

```ts
if (!normalizedKey || !normalizedName) {
  throw new Error('Mã quy trình và tên quy trình không được để trống')
}
if (!PROCESS_DEFINITION_KEY_PATTERN.test(normalizedKey)) {
  throw new Error(
    'Mã quy trình phải bắt đầu bằng chữ cái và chỉ gồm chữ cái, số, gạch dưới và gạch ngang',
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/processDefinitionDomain.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/process-definition/domain.ts workflow-agent-web/src/__tests__/processDefinitionDomain.spec.ts
git commit -m "feat: validate process identity with Vietnamese errors"
```

---

### Task 4: Lỗi biến khi khởi chạy

**Files:**
- Modify: `workflow-agent-web/src/features/process-instance/startProcess.ts`
- Test: `workflow-agent-web/src/__tests__/startProcess.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `buildProcessVariables(rows): Record<string, unknown>`. Giá trị biến không bị dịch.

- [ ] **Step 1: Write the failing test**

Đổi hai assertion lỗi:

```ts
).toThrow('Tên biến nghiệp vụ bị trùng')
```

```ts
).toThrow('phải là JSON hợp lệ')
```

```ts
).toThrow('Thiết lập người tham gia')
```

Giữ giá trị mẫu `请假`.

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/startProcess.spec.ts`

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

```ts
if (!name) throw new Error('Tên biến nghiệp vụ không được để trống')
if (INTERNAL_PARTICIPANT_SUFFIXES.some((suffix) => name.endsWith(suffix))) {
  throw new Error(
    'Hãy chọn người tham gia trong “Thiết lập người tham gia”, không điền biến kỹ thuật',
  )
}
if (Object.prototype.hasOwnProperty.call(variables, name)) {
  throw new Error(`Tên biến nghiệp vụ bị trùng: ${name}`)
}
```

Trong `parseVariableValue`:

```ts
if (!row.value.trim() || !Number.isFinite(value)) throw new Error(`${name} phải là số hợp lệ`)
```

```ts
throw new Error(`${name} phải là JSON hợp lệ`)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/startProcess.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/process-instance/startProcess.ts workflow-agent-web/src/__tests__/startProcess.spec.ts
git commit -m "feat: report start-process variable errors in Vietnamese"
```

---

### Task 5: Lỗi dữ liệu tương tác

**Files:**
- Modify: `workflow-agent-web/src/features/process-instance/interaction.ts`
- Test: `workflow-agent-web/src/features/process-instance/interaction.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: cùng `buildInteractionVariables` và `mergeInteractionVariables`. `field.label` là dữ liệu, không dịch.

- [ ] **Step 1: Write the failing test**

Đổi assertion thành:

```ts
expect(() => buildInteractionVariables([field({})], {})).toThrow('Hãy điền “客户名称”')
```

Giữ các nhãn mẫu `客户名称`, `金额`, `加急`, `测试客户`.

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/features/process-instance/interaction.spec.ts`

Expected: FAIL vì message vẫn là `请填写“客户名称”`.

- [ ] **Step 3: Write minimal implementation**

```ts
if (field.required && isMissing(value)) throw new Error(`Hãy điền “${field.label}”`)
```

```ts
if (!Number.isFinite(value)) throw new Error(`“${field.label}” phải là số`)
if (field.dataType === 'integer' && !Number.isInteger(value)) {
  throw new Error(`“${field.label}” phải là số nguyên`)
}
```

```ts
throw new Error(`“${field.label}” phải chọn Có hoặc Không`)
```

```ts
throw new Error(
  `“${field.label}” không phải JSON ${field.dataType === 'array' ? 'mảng' : 'đối tượng'} hợp lệ`,
)
```

```ts
throw new Error(`Đường dẫn biến “${path}” xung đột với biến đã có`)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/features/process-instance/interaction.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/process-instance/interaction.ts workflow-agent-web/src/features/process-instance/interaction.spec.ts
git commit -m "feat: report interaction field errors in Vietnamese"
```

---

### Task 6: Lỗi XML BPMN

**Files:**
- Modify: `workflow-agent-web/src/utils/bpmn.ts`
- Test: `workflow-agent-web/src/__tests__/bpmn.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `validateBpmnXml(xml: string): Document`. Tên quy trình trong XML là dữ liệu.

- [ ] **Step 1: Write the failing test**

Đổi assertion malformed XML thành:

```ts
expect(() => validateBpmnXml('<definitions>')).toThrow('BPMN XML không đúng định dạng')
```

Giữ `费用审批`, `提交申请`, `部门审批` trong XML mẫu.

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/bpmn.spec.ts`

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

```ts
if (parserError) throw new Error('BPMN XML không đúng định dạng')
```

```ts
if (!process?.getAttribute('id')) throw new Error('BPMN XML thiếu process id')
```

```ts
throw new Error('Tác vụ Agent phải gắn trên nút chờ bpmn:receiveTask')
```

```ts
if (!Number.isInteger(version) || version <= 0) {
  throw new Error('Tác vụ Agent phải chọn phiên bản đã phát hành')
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/bpmn.spec.ts`

Expected: PASS. Assertion `receiveTask` vẫn khớp vì câu mới còn chữ `receiveTask`.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/utils/bpmn.ts workflow-agent-web/src/__tests__/bpmn.spec.ts
git commit -m "feat: report BPMN validation errors in Vietnamese"
```

---

### Task 7: Câu dự phòng của HTTP client

**Files:**
- Modify: `workflow-agent-web/src/api/http.ts`
- Test: `workflow-agent-web/src/__tests__/httpFallback.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `fallbackHttpMessage(status: number | undefined, transportMessage: string): string` và `invalidResponseMessage`, `operationFailedMessage`, `missingCsrfMessage` là các hằng xuất ra để test. Interceptor vẫn ưu tiên `body.message` nếu có.

- [ ] **Step 1: Write the failing test**

Tạo `workflow-agent-web/src/__tests__/httpFallback.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  fallbackHttpMessage,
  invalidResponseMessage,
  missingCsrfMessage,
  operationFailedMessage,
} from '@/api/http'

describe('HTTP fallback copy', () => {
  it('uses Vietnamese text when the server sends no message', () => {
    expect(fallbackHttpMessage(401, 'Unauthorized')).toBe('Phiên đăng nhập đã hết hạn')
    expect(fallbackHttpMessage(403, 'Forbidden')).toBe(
      'Bạn không có quyền thực hiện thao tác này',
    )
    expect(fallbackHttpMessage(500, '')).toBe('Yêu cầu mạng thất bại')
    expect(fallbackHttpMessage(500, 'timeout of 15000ms exceeded')).toBe(
      'timeout of 15000ms exceeded',
    )
    expect(invalidResponseMessage).toBe('Phản hồi của máy chủ không đúng định dạng')
    expect(operationFailedMessage).toBe('Thao tác thất bại')
    expect(missingCsrfMessage).toBe('Không tạo được ngữ cảnh yêu cầu an toàn')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/httpFallback.spec.ts`

Expected: FAIL vì các export chưa tồn tại.

- [ ] **Step 3: Write minimal implementation**

Thêm vào `http.ts`, phía trên interceptor:

```ts
export const invalidResponseMessage = 'Phản hồi của máy chủ không đúng định dạng'
export const operationFailedMessage = 'Thao tác thất bại'
export const missingCsrfMessage = 'Không tạo được ngữ cảnh yêu cầu an toàn'

export function fallbackHttpMessage(status: number | undefined, transportMessage: string) {
  if (status === 401) return 'Phiên đăng nhập đã hết hạn'
  if (status === 403) return 'Bạn không có quyền thực hiện thao tác này'
  return transportMessage || 'Yêu cầu mạng thất bại'
}
```

Trong interceptor, giữ `body?.message` đứng trước:

```ts
const message = body?.message || fallbackHttpMessage(error.response?.status, error.message || '')
```

Trong `unwrap` và `withCsrf`, dùng ba hằng trên thay cho câu tiếng Trung.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/httpFallback.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/api/http.ts workflow-agent-web/src/__tests__/httpFallback.spec.ts
git commit -m "feat: show HTTP fallback errors in Vietnamese"
```

---

### Task 8: Catalog lỗi Agent

**Files:**
- Modify: `workflow-agent-web/src/features/agent/errorPresentation.ts`
- Test: `workflow-agent-web/src/features/agent/errorPresentation.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `getAgentErrorPresentation(code?: string | null): AgentErrorPresentation | null`. Mã `AGENT_OUTPUT_NOT_JSON` và các mã cùng kiểu không đổi. `action` không đổi.

- [ ] **Step 1: Write the failing test**

Tạo `errorPresentation.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { getAgentErrorPresentation } from './errorPresentation'

describe('agent error presentation', () => {
  it('describes a known provider timeout in Vietnamese', () => {
    expect(getAgentErrorPresentation('PROVIDER_TIMEOUT')).toMatchObject({
      code: 'PROVIDER_TIMEOUT',
      title: 'Yêu cầu tới nhà cung cấp mô hình bị hết thời gian',
      action: 'RETRY_PROVIDER',
      actionLabel: 'Có thể thử lại theo chiến lược',
    })
  })

  it('describes an unknown code without translating the code', () => {
    expect(getAgentErrorPresentation('UNKNOWN_CODE')).toMatchObject({
      code: 'UNKNOWN_CODE',
      title: 'Agent chưa chạy xong',
      action: 'WAIT_FOR_REVIEW',
      actionLabel: 'Cần quản trị viên xử lý',
    })
  })

  it('returns null when there is no code', () => {
    expect(getAgentErrorPresentation(null)).toBeNull()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/features/agent/errorPresentation.spec.ts`

Expected: FAIL vì title vẫn là tiếng Trung.

- [ ] **Step 3: Write minimal implementation**

Thay `catalog` và nhánh fallback. Giữ nguyên kiểu `AgentRecoveryAction` và khóa `action`:

```ts
const catalog: Record<string, Omit<AgentErrorPresentation, 'code'>> = {
  AGENT_OUTPUT_NOT_JSON: {
    title: 'Kết quả mô hình không phải JSON hợp lệ',
    description:
      'Hệ thống đã thử sửa một lần theo hợp đồng đầu ra. Nếu vẫn thất bại, hãy kiểm tra cấu hình trường đầu ra hoặc xử lý thủ công.',
    action: 'REPAIR_OUTPUT',
    actionLabel: 'Đã sửa đầu ra một lần',
  },
  AGENT_OUTPUT_SCHEMA_INVALID: {
    title: 'Kết quả mô hình không khớp hợp đồng đầu ra',
    description:
      'Mô hình trả về JSON, nhưng trường, kiểu hoặc mục bắt buộc không khớp Schema của phiên bản Agent hiện tại.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần kiểm tra cấu hình trường đầu ra',
  },
  AGENT_INPUT_NOT_JSON: {
    title: 'Dữ liệu đầu vào không phải JSON hợp lệ',
    description:
      'Agent này có hợp đồng đầu vào. Hãy kiểm tra ánh xạ biến quy trình và kiểu trường đầu vào.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần sửa cấu hình đầu vào',
  },
  AGENT_INPUT_SCHEMA_INVALID: {
    title: 'Dữ liệu đầu vào không khớp hợp đồng đầu vào',
    description:
      'Dữ liệu từ quy trình thiếu trường bắt buộc, hoặc kiểu trường không khớp Schema đầu vào của Agent.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần sửa ánh xạ đầu vào',
  },
  AGENT_TOOL_CALL_INVALID: {
    title: 'Lời gọi công cụ không đúng định dạng',
    description:
      'Mô hình không tạo được lời gọi công cụ có thể thực thi. Hệ thống giới hạn số lần sửa để tránh thử lại vô hạn.',
    action: 'REPAIR_TOOL_CALL',
    actionLabel: 'Đã sửa lời gọi công cụ một lần',
  },
  AGENT_RESULT_EMPTY: {
    title: 'Agent không tạo kết quả',
    description:
      'Lượt gọi mô hình đã xong nhưng không có nội dung dùng được. Hãy kiểm tra prompt, cấu hình mô hình và chiến lược kết quả.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần kiểm tra chiến lược kết quả',
  },
  PROVIDER_UNAVAILABLE: {
    title: 'Nhà cung cấp mô hình tạm thời không dùng được',
    description:
      'Đây là lỗi hạ tầng tạm thời. Hệ thống xử lý theo ngân sách thử lại và chiến lược lùi thời gian.',
    action: 'RETRY_PROVIDER',
    actionLabel: 'Có thể thử lại theo chiến lược',
  },
  PROVIDER_TIMEOUT: {
    title: 'Yêu cầu tới nhà cung cấp mô hình bị hết thời gian',
    description:
      'Yêu cầu vượt quá thời gian chờ của phiên bản Agent hiện tại. Hệ thống không gọi lại vô hạn.',
    action: 'RETRY_PROVIDER',
    actionLabel: 'Có thể thử lại theo chiến lược',
  },
  AGENT_CONFIGURATION_ERROR: {
    title: 'Cấu hình Agent không dùng được',
    description:
      'Phiên bản đã phát hành thiếu nhà cung cấp, thông tin xác thực hoặc cấu hình thực thi. Quản trị viên cần kiểm tra.',
    action: 'CONFIGURATION',
    actionLabel: 'Cần quản trị viên xử lý',
  },
  AGENT_EXECUTION_ERROR: {
    title: 'Agent chạy gặp lỗi',
    description:
      'Quá trình chạy gặp lỗi chưa phân loại. Hãy giữ số lần chạy và Trace ID để quản trị viên kiểm tra.',
    action: 'WAIT_FOR_REVIEW',
    actionLabel: 'Cần quản trị viên kiểm tra',
  },
}
```

Fallback:

```ts
title: 'Agent chưa chạy xong',
description:
  'Hệ thống đã ghi nhận lần thất bại này nhưng chưa có chiến lược khôi phục cụ thể hơn. Hãy liên hệ quản trị viên.',
action: 'WAIT_FOR_REVIEW',
actionLabel: 'Cần quản trị viên xử lý',
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/features/agent/errorPresentation.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/agent/errorPresentation.ts workflow-agent-web/src/features/agent/errorPresentation.spec.ts
git commit -m "feat: describe agent failures in Vietnamese"
```

---

### Task 9: Ngôn ngữ trang và Element Plus

**Files:**
- Modify: `workflow-agent-web/index.html`
- Modify: `workflow-agent-web/src/App.vue`
- Test: `workflow-agent-web/src/__tests__/App.spec.ts`

**Interfaces:**
- Consumes: không
- Produces: `ElConfigProvider` nhận locale `vi`. Tài liệu HTML có `lang="vi"`.

- [ ] **Step 1: Write the failing test**

Thêm vào `App.spec.ts`:

```ts
it('configures Element Plus with the Vietnamese locale', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<main>Workflow workspace</main>' } }],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(App, { global: { plugins: [router] } })

  expect(wrapper.findComponent({ name: 'ElConfigProvider' }).props('locale')).toMatchObject({
    name: 'vi',
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest --run src/__tests__/App.spec.ts`

Expected: FAIL vì locale hiện là `zh-cn`.

- [ ] **Step 3: Write minimal implementation**

`App.vue`:

```ts
import vi from 'element-plus/es/locale/lang/vi'
```

```vue
<ElConfigProvider :locale="vi">
  <RouterView />
</ElConfigProvider>
```

`index.html`: đổi `lang="zh-CN"` thành `lang="vi"`. Giữ `<title>Workflow Agent</title>`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm exec vitest --run src/__tests__/App.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/index.html workflow-agent-web/src/App.vue workflow-agent-web/src/__tests__/App.spec.ts
git commit -m "feat: use the Vietnamese Element Plus locale"
```

---

### Task 10: Tiêu đề tuyến và menu

**Files:**
- Modify: `workflow-agent-web/src/router/index.ts`

**Interfaces:**
- Consumes: từ vựng trong Global Constraints
- Produces: `route.meta.title` và `route.meta.navigation.label` bằng tiếng Việt. Tên route kỹ thuật (`process-definitions`, …) không đổi.

- [ ] **Step 1: Replace the visible route copy**

Đổi đúng các giá trị sau, không đổi path, name, role, permission:

```ts
meta: { title: 'Đăng nhập', public: true }
```

```ts
title: 'Định nghĩa quy trình'
navigation: { label: 'Định nghĩa quy trình', icon: 'definitions', order: 10 }
```

```ts
title: 'Thiết kế quy trình'
```

```ts
title: 'Phiên chạy'
navigation: { label: 'Phiên chạy', icon: 'instances', order: 20 }
```

```ts
title: 'Chi tiết phiên chạy'
```

```ts
title: 'Danh mục dịch vụ'
navigation: { label: 'Danh mục dịch vụ', icon: 'services', order: 35 }
```

```ts
title: 'Quy tắc phân công'
navigation: { label: 'Quy tắc phân công', icon: 'assignments', order: 30 }
```

```ts
title: 'Thành viên và vai trò'
navigation: { label: 'Thành viên và vai trò', icon: 'access', order: 50 }
```

```ts
title: 'Trung tâm Agent'
navigation: { label: 'Trung tâm Agent', icon: 'agents', order: 40 }
```

```ts
title: 'Quản lý tenant'
navigation: { label: 'Quản lý tenant', icon: 'tenants', order: 60 }
```

```ts
meta: { title: 'Không tìm thấy trang' }
```

- [ ] **Step 2: Confirm no Chinese remains in the router**

Run từ repo root:

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/router/index.ts
```

Expected: không có dòng khớp.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/router/index.ts
git commit -m "feat: name navigation routes in Vietnamese"
```

---

### Task 11: Khung ứng dụng, trang 404, phân trang

**Files:**
- Modify: `workflow-agent-web/src/layouts/AppShell.vue`
- Modify: `workflow-agent-web/src/views/NotFoundView.vue`
- Modify: `workflow-agent-web/src/components/TablePagination.vue`

**Interfaces:**
- Consumes: `route.meta.title` từ Task 10. Fallback tiêu đề khi meta trống là `Bàn làm việc`. Fallback tên người là `Người dùng`.
- Produces: aria-label menu `Thu gọn menu` và `Mở rộng menu`.

- [ ] **Step 1: Replace shell, 404, and pagination copy**

Trong `AppShell.vue`:

```ts
const pageTitle = computed(() => String(route.meta.title ?? 'Bàn làm việc'))
```

```ts
() => authStore.user?.displayName || authStore.user?.username || 'Người dùng'
```

```ts
if (showError) ElMessage.error('Không thể làm mới quyền của tài khoản hiện tại')
```

```ts
ElMessage.success(
  `Đã chuyển sang ${authStore.tenants.find((item) => item.tenantCode === tenantCode)?.tenantName || tenantCode}`,
)
```

```vue
:title="sidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'"
:aria-label="sidebarCollapsed ? 'Mở rộng menu' : 'Thu gọn menu'"
```

Dịch mọi chuỗi tiếng Trung còn lại trong template và `ElMessage` của file này theo bảng từ vựng. Không dịch `displayName` hay `tenantName` lấy từ API.

`NotFoundView.vue`:

```vue
<h1>Không tìm thấy trang</h1>
<RouterLink to="/process-definitions"><ArrowLeft :size="16" />Về định nghĩa quy trình</RouterLink>
```

`TablePagination.vue`:

```ts
ariaLabel: 'Phân trang',
```

```vue
v-accessible-label="'Số dòng mỗi trang'"
```

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/layouts/AppShell.vue workflow-agent-web/src/views/NotFoundView.vue workflow-agent-web/src/components/TablePagination.vue
```

Expected: không có dòng khớp.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/layouts/AppShell.vue workflow-agent-web/src/views/NotFoundView.vue workflow-agent-web/src/components/TablePagination.vue
git commit -m "feat: show the app shell in Vietnamese"
```

---

### Task 12: Đăng nhập

**Files:**
- Modify: `workflow-agent-web/src/features/auth/views/AuthView.vue`

**Interfaces:**
- Consumes: từ vựng. Nút chính là `Đăng nhập`.
- Produces: không có chữ tiếng Trung trong file.

- [ ] **Step 1: Replace every user-visible Chinese string**

Dùng bảng từ vựng. Câu không có trong bảng thì dịch sát nghĩa, giọng câu lệnh ngắn giống các câu lỗi HTTP. Không đổi tên trường form gửi lên API (`username`, `password`, `tenantCode`).

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/auth/views/AuthView.vue
```

Expected: không có dòng khớp.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/auth/views/AuthView.vue
git commit -m "feat: show the sign-in screen in Vietnamese"
```

---

### Task 13: Định nghĩa quy trình và trình thiết kế

**Files:**
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDefinitionListView.vue`
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue`
- Modify: `workflow-agent-web/src/features/process-definition/components/AgentTaskConfigPanel.vue`
- Modify: `workflow-agent-web/src/bpmn/workflowPaletteModule.ts`

**Interfaces:**
- Consumes: `formatVersion`, `normalizeProcessIdentity`
- Produces: các nhãn e2e của màn định nghĩa và thiết kế, đúng bảng chữ bị khóa.

- [ ] **Step 1: Replace locked strings**

Trong list:

- `新建流程` → `Tạo quy trình`
- `流程标识` → `Mã quy trình`
- `流程名称` → `Tên quy trình`
- `发布状态` → `Trạng thái phát hành`
- `创建并设计` → `Tạo và thiết kế`
- `流程标识已存在：${key}` → `Mã quy trình đã tồn tại: ${key}`

Trong designer, map loại phần tử:

```ts
'bpmn:StartEvent': 'Sự kiện bắt đầu',
'bpmn:EndEvent': 'Sự kiện kết thúc',
'bpmn:UserTask': 'Nhiệm vụ người dùng',
'bpmn:Task': 'Nhiệm vụ',
'bpmn:ServiceTask': 'Nhiệm vụ dịch vụ',
'bpmn:ExclusiveGateway': 'Cổng loại trừ',
'bpmn:ParallelGateway': 'Cổng song song',
'bpmn:InclusiveGateway': 'Cổng bao hàm',
'bpmn:SequenceFlow': 'Đường nối',
'bpmn:SubProcess': 'Quy trình con',
```

Nhãn listener:

```ts
{ kind: 'executionStart', label: 'Bộ lắng nghe lúc bắt đầu' },
{ kind: 'executionEnd', label: 'Bộ lắng nghe lúc kết thúc' },
{ kind: 'taskCreate', label: 'Bộ lắng nghe lúc tạo nhiệm vụ', taskOnly: true },
{ kind: 'taskComplete', label: 'Bộ lắng nghe lúc hoàn thành nhiệm vụ', taskOnly: true },
```

Các nút và tiêu đề: `Tạo sơ đồ mới`, `Lưu phiên bản mới`, `Tổng quan quy trình`, `Quản lý phiên bản`, `Cấu hình phần tử`, `Tên quy trình`.

`workflowPaletteModule.ts`: `title: 'Tạo nhiệm vụ người dùng'`.

Dịch mọi chuỗi tiếng Trung còn lại trong bốn file. Không đổi `agentVersionId`, XML, hay tên thuộc tính BPMN.

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/process-definition workflow-agent-web/src/bpmn/workflowPaletteModule.ts
```

Expected: không có dòng khớp. `domain.ts` đã xong ở Task 3.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/process-definition/views/ProcessDefinitionListView.vue workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue workflow-agent-web/src/features/process-definition/components/AgentTaskConfigPanel.vue workflow-agent-web/src/bpmn/workflowPaletteModule.ts
git commit -m "feat: show the process designer in Vietnamese"
```

---

### Task 14: Phiên chạy

**Files:**
- Modify: `workflow-agent-web/src/features/process-instance/views/ProcessInstanceListView.vue`
- Modify: `workflow-agent-web/src/features/process-instance/views/ProcessInstanceDetailView.vue`
- Modify: `workflow-agent-web/src/features/process-instance/components/StartProcessDialog.vue`
- Modify: `workflow-agent-web/src/features/process-instance/components/ParticipantAssignmentEditor.vue`
- Modify: `workflow-agent-web/src/features/process-instance/components/UserPickerDialog.vue`
- Modify: `workflow-agent-web/src/features/process-instance/components/InteractionDataFields.vue`
- Modify: `workflow-agent-web/src/features/process-instance/components/ProcessDiagram.vue`

**Interfaces:**
- Consumes: `formatDateTime`, `formatVersion`, `formatDuration`, `buildProcessVariables`, `buildInteractionVariables`
- Produces: chữ e2e của hộp khởi chạy, đồng ý nhiệm vụ, và chọn người tham gia.

- [ ] **Step 1: Replace locked strings**

`StartProcessDialog.vue`: đổi `localeCompare(..., 'zh-CN')` thành `localeCompare(..., 'vi')`.

- `业务标识` → `Mã nghiệp vụ`
- `输入文本` → `Nhập văn bản`
- `添加变量` → `Thêm biến`
- `确认发起` → `Xác nhận khởi chạy`
- `发起流程` → `Khởi chạy quy trình`
- Nút `发起` đúng một mình → `Khởi chạy`
- `例如 amount` → `ví dụ amount`

`InteractionDataFields.vue`:

- `Agent 业务输入` → `Dữ liệu nghiệp vụ cho Agent`
- Câu chưa khai báo dữ liệu → câu đã khóa trong Global Constraints

`ParticipantAssignmentEditor.vue`:

- `参与人设置` → `Thiết lập người tham gia`
- `可选指定` → `Có thể chỉ định`
- `未指定，将使用派单规则` → `Chưa chỉ định, sẽ dùng quy tắc phân công`
- `选择参与人` → `Chọn người tham gia`
- Tiêu đề: `` `Chọn người tham gia cho “${activeRequirement.activityName}”` ``

`UserPickerDialog.vue`: `确认选择` → `Xác nhận lựa chọn`

`ProcessInstanceDetailView.vue`:

- `业务标识` → `Mã nghiệp vụ`
- `流程跟踪` → `Theo dõi quy trình`
- `` `Nhiệm vụ (${count})` `` thay cho `` `任务 (${count})` ``
- `同意` → `Đồng ý`
- `同意任务` → `Đồng ý nhiệm vụ`
- `退回任务` → `Trả lại nhiệm vụ`
- `确认同意` → `Xác nhận đồng ý`
- `确认退回` → `Xác nhận trả lại`
- `任务已同意` → `Đã đồng ý nhiệm vụ`
- `任务已退回` → `Đã trả lại nhiệm vụ`

Dịch mọi chuỗi tiếng Trung còn lại trong bảy file. Không dịch tên nhiệm vụ, tên quy trình, hay nhãn trường đến từ API.

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/process-instance --glob "!*.spec.ts"
```

Expected: không có dòng khớp. `interaction.ts` và `startProcess.ts` đã xong ở task trước.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/process-instance/views workflow-agent-web/src/features/process-instance/components
git commit -m "feat: show process runs in Vietnamese"
```

---

### Task 15: Phân công, thành viên, tenant

**Files:**
- Modify: `workflow-agent-web/src/features/assignment-rule/views/AssignmentRuleListView.vue`
- Modify: `workflow-agent-web/src/features/assignment-rule/components/RuleUserPickerField.vue`
- Modify: `workflow-agent-web/src/features/access/views/AccessManagementView.vue`
- Modify: `workflow-agent-web/src/features/tenant/views/TenantListView.vue`

**Interfaces:**
- Consumes: từ vựng. `派单规则` là `Quy tắc phân công`. `租户` là `Tenant`.
- Produces: nút `Thêm quy tắc`, nhãn `Mã quy trình`.

- [ ] **Step 1: Replace visible copy**

Khóa thêm trong màn quy tắc:

- `新增规则` → `Thêm quy tắc`
- `流程标识` → `Mã quy trình`
- `该版本没有可配置的用户任务` → `Phiên bản này không có nhiệm vụ người dùng để cấu hình`

Các file còn lại: dịch hết chuỗi tiếng Trung người dùng thấy. Tên người, mã tenant và tên tenant từ API giữ nguyên.

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/assignment-rule workflow-agent-web/src/features/access workflow-agent-web/src/features/tenant
```

Expected: không có dòng khớp.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/assignment-rule workflow-agent-web/src/features/access workflow-agent-web/src/features/tenant
git commit -m "feat: show assignment, access, and tenant screens in Vietnamese"
```

---

### Task 16: Trung tâm Agent

**Files:**
- Modify: `workflow-agent-web/src/features/agent/views/AgentManagementView.vue`
- Modify: `workflow-agent-web/src/features/agent/components/SchemaFieldsEditor.vue`
- Modify: `workflow-agent-web/src/features/agent/components/AgentProviderTable.vue`

**Interfaces:**
- Consumes: `getAgentErrorPresentation` từ Task 8, `formatVersion`
- Produces: tab `Định nghĩa Agent`, tiêu đề có `Quản lý phiên bản`. Mã lỗi và từ `Agent` giữ nguyên.

- [ ] **Step 1: Replace visible copy**

- `Agent 定义` → `Định nghĩa Agent`
- `版本管理` → `Quản lý phiên bản`
- `Agent 定义分页` → `Phân trang định nghĩa Agent`

Dịch mọi chuỗi tiếng Trung còn lại trong ba file. Không đổi khóa JSON Schema, tên model, hay mã `action`.

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/agent --glob "!errorPresentation.spec.ts"
```

Expected: không có dòng khớp. `errorPresentation.ts` đã hết tiếng Trung từ Task 8.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/agent/views/AgentManagementView.vue workflow-agent-web/src/features/agent/components/SchemaFieldsEditor.vue workflow-agent-web/src/features/agent/components/AgentProviderTable.vue
git commit -m "feat: show the agent center in Vietnamese"
```

---

### Task 17: Danh mục dịch vụ

**Files:**
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCallConfigPanel.vue`

**Interfaces:**
- Consumes: từ vựng. `服务目录` đã nằm ở router.
- Produces: hai file không còn chữ tiếng Trung. Khóa `serviceKey`, `operationKey`, method HTTP giữ nguyên.

- [ ] **Step 1: Replace visible copy**

Dịch nhãn, nút, placeholder, thông báo và aria-label. Giữ `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `Bearer`, `Basic`.

- [ ] **Step 2: Confirm no Chinese remains**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src/features/service-registry
```

Expected: không có dòng khớp.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue workflow-agent-web/src/features/service-registry/ServiceCallConfigPanel.vue
git commit -m "feat: show the service catalog in Vietnamese"
```

---

### Task 18: Assertion e2e của chữ giao diện

**Files:**
- Modify: `workflow-agent-web/e2e/workflow.spec.ts`

**Interfaces:**
- Consumes: chữ đã khóa ở các task giao diện
- Produces: selector chữ giao diện là tiếng Việt. Dữ liệu mock tiếng Trung giữ nguyên.

- [ ] **Step 1: Update chrome assertions only**

Đổi các selector sau. Không đổi payload mock, tên `费用审批`, `主管审批`, `人事复核`, `平台管理员`, `试用用户`, `默认租户`, `本地 Mock`, `费用审核 Agent`, hay `getByLabel('客户名称')` vì nhãn đó đến từ API.

```ts
await expect(page.getByRole('heading', { name: 'Định nghĩa quy trình', exact: true })).toBeVisible()
await expect(page.locator('.el-select__input[aria-label="Trạng thái phát hành"]')).toHaveCount(1)
const collapseButton = page.getByRole('button', { name: 'Thu gọn menu' })
await expect(page.getByRole('button', { name: 'Mở rộng menu' })).toHaveAttribute(
await page.getByRole('button', { name: 'Mở rộng menu' }).click()
await expect(page.getByRole('button', { name: 'Tạo quy trình' }).first()).toBeVisible()
await page.getByRole('button', { name: 'Khởi chạy', exact: true }).click()
const startDialog = page.getByRole('dialog', { name: 'Khởi chạy quy trình' })
await expect(startDialog.getByText(/费用审批.*Phiên bản 2/)).toBeVisible()
await startDialog.getByLabel('Mã nghiệp vụ').fill('EXP-2026-002')
await startDialog.getByRole('button', { name: 'Khởi chạy quy trình' }).click()
await page.getByRole('tab', { name: 'Nhiệm vụ (1)' }).click()
await page.getByRole('button', { name: 'Đồng ý' }).click()
const decisionDialog = page.getByRole('dialog', { name: 'Đồng ý nhiệm vụ' })
await decisionDialog.getByRole('button', { name: 'Chọn người tham gia' }).click()
const picker = page.getByRole('dialog', { name: 'Chọn người tham gia cho “人事复核”' })
await picker.getByRole('button', { name: 'Xác nhận lựa chọn' }).click()
await decisionDialog.getByRole('button', { name: 'Xác nhận đồng ý' }).click()
await expect(page.getByText('Đã đồng ý nhiệm vụ')).toBeVisible()
await page.getByRole('tab', { name: 'Theo dõi quy trình' }).click()
await page.getByRole('button', { name: 'Tạo quy trình' }).first().click()
const dialog = page.getByRole('dialog', { name: 'Tạo quy trình' })
await dialog.getByLabel('Mã quy trình').fill('leave_approval')
await dialog.getByLabel('Tên quy trình').fill('请假审批')
await dialog.getByRole('button', { name: 'Tạo và thiết kế' }).click()
await expect(page.getByLabel('Tên quy trình')).toHaveValue('请假审批')
await expect(page.getByText('Tổng quan quy trình', { exact: true })).toBeVisible()
await expect(page.getByText('Quản lý phiên bản', { exact: true })).toBeVisible()
await expect(page.getByText('Cấu hình phần tử', { exact: true })).toBeVisible()
await expect(page.getByRole('button', { name: 'Tạo sơ đồ mới' })).toBeVisible()
await expect(page.getByRole('button', { name: 'Lưu phiên bản mới' })).toBeVisible()
await expect(userTaskPaletteEntry).toHaveAttribute('title', 'Tạo nhiệm vụ người dùng')
await expect(page.getByText('Nhiệm vụ người dùng', { exact: true })).toBeVisible()
await startDialog.getByRole('button', { name: 'Thêm biến' }).click()
await startDialog.getByPlaceholder('ví dụ amount').fill('manualParticipants')
await startDialog.getByPlaceholder('Nhập văn bản').fill('yes')
await expect(startDialog.getByText('Thiết lập người tham gia')).toBeVisible()
const picker = page.getByRole('dialog', { name: 'Chọn người tham gia cho “主管审批”' })
await startDialog.getByRole('button', { name: /^(Xác nhận khởi chạy|Khởi chạy quy trình)$/ }).click()
await expect(startDialog.getByText('Dữ liệu nghiệp vụ cho Agent')).toBeVisible()
'Nút Agent sắp tới không khai báo dữ liệu nghiệp vụ cần người điền. Quy trình sẽ chạy theo cấu hình đã phát hành.'
await expect(startDialog.getByText('Có thể chỉ định')).toBeVisible()
await expect(startDialog.getByText('Chưa chỉ định, sẽ dùng quy tắc phân công')).toBeVisible()
await startDialog.getByRole('button', { name: 'Xác nhận khởi chạy' }).click()
await page.getByRole('link', { name: 'Quy tắc phân công' }).click()
await expect(page.getByRole('button', { name: 'Thêm quy tắc' })).toBeVisible()
await page.getByRole('link', { name: 'Quản lý tenant' }).click()
await expect(page.getByRole('heading', { level: 1, name: 'Trung tâm Agent' })).toBeVisible()
const definitionPanel = page.getByLabel('Định nghĩa Agent')
await expect(definitionPanel.getByRole('row', { name: /费用审核 Agent.*Phiên bản 1/ })).toBeVisible()
```

Giữ `await expect(page.getByText('费用审批')).toBeVisible()`, `主管审批`, `人事复核`, `默认租户`, `费用审核 Agent`, và `getByLabel('客户名称')`.

- [ ] **Step 2: Run the e2e file**

Run từ `workflow-agent-web`: `pnpm exec playwright test e2e/workflow.spec.ts --project=chromium`

Expected: PASS. Nếu fail vì selector còn lệch một chữ, sửa file Vue cho khớp bảng đã khóa rồi chạy lại. Không đổi dữ liệu mock để né lỗi.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-web/e2e/workflow.spec.ts
git commit -m "test: expect Vietnamese labels in the workflow e2e"
```

Nếu step 2 buộc sửa thêm file Vue, add cả file đó vào commit này.

---

### Task 19: Rà soát toàn bộ chữ giao diện

**Files:**
- Modify: chỉ file trong `workflow-agent-web` còn sót tiếng Trung ở chữ người dùng thấy

**Interfaces:**
- Consumes: mọi task trước
- Produces: `workflow-agent-web/src` không còn ký tự Trung ở chữ hiển thị. Ngoại lệ duy nhất là `，` trong `splitValues`.

- [ ] **Step 1: Run the unit tests**

Run từ `workflow-agent-web`: `pnpm test:unit:run`

Expected: PASS

- [ ] **Step 2: Search leftover Chinese**

```bash
rg "[\u4e00-\u9fff]" workflow-agent-web/src workflow-agent-web/index.html
```

Expected: chữ người dùng thấy không còn ký tự Trung. Được phép còn dấu `，` trong `splitValues` của `workflow-agent-web/src/utils/format.ts`, và comment `//` hoặc `/* */` nếu comment không được render. Mọi string và template còn lại phải được dịch rồi commit theo file của task tương ứng, hoặc một commit `feat: finish leftover Vietnamese UI copy` nếu sót ở file chưa có task riêng.

- [ ] **Step 3: Walk the UI**

Mở app, đăng nhập, xem menu, một danh sách, một ngày giờ dạng `DD-MM-YYYY HH:mm:ss`, và vài nhãn trên trình thiết kế (`Nhiệm vụ người dùng`, `Tạo sơ đồ mới`). `message` lỗi do server trả về có thể vẫn là tiếng Trung.
