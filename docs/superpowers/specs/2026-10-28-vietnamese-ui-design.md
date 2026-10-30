# Việt hóa giao diện workflow-agent

## Mục tiêu

Giao diện web chỉ hiển thị tiếng Việt. Không có nút chuyển ngôn ngữ và không thêm thư viện đa ngôn ngữ.

## Phạm vi

Có:

- Mọi chuỗi tiếng Trung mà người dùng nhìn thấy trong `workflow-agent-web`: menu, tiêu đề, nhãn, nút, placeholder, hộp thoại, thông báo, trạng thái trống, aria-label, lỗi do giao diện tự viết.
- Locale Element Plus đổi từ `zh-cn` sang `vi`.
- `index.html`: `lang="vi"`. Tiêu đề trang giữ `Workflow Agent`.
- Ngày giờ hiển thị `DD-MM-YYYY HH:mm:ss`. Giá trị rỗng vẫn là `-`.
- Sắp xếp tên quy trình dùng `localeCompare(..., 'vi')`.
- Nhãn do ứng dụng đặt trong trình thiết kế BPMN.
- Test đơn vị và e2e đang so khớp chữ giao diện tiếng Trung.

Không có:

- Backend, thông báo API, tên vai trò lưu trong cơ sở dữ liệu.
- `vue-i18n` hoặc file từ điển.
- Dịch `message` mà API trả về. Giao diện hiển thị đúng chuỗi server gửi.
- Dữ liệu mẫu trong mock và test (tên quy trình, tên người, tên tenant, nội dung Agent). Đó là dữ liệu, không phải chữ giao diện.
- Chữ có sẵn của bpmn-js khi thư viện đang để tiếng Anh.
- Comment trong code.
- Dấu phẩy Trung `，` trong bộ tách giá trị nhập. Giữ để chuỗi đã dán vẫn tách được.

## Cách làm

Thay chuỗi tiếng Trung bằng tiếng Việt ngay trong file Vue và TypeScript. Mã quyền, tên trường API, loại phần tử BPMN và từ `Agent` giữ tiếng Anh.

`App.vue` import `element-plus/es/locale/lang/vi`.

## Từ vựng

Cùng một khái niệm dùng một cách gọi.

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

Thời lượng: `N giây`, `N phút S giây`, `N giờ M phút`.

Hành động lặp lại:

| Tiếng Trung | Tiếng Việt |
| --- | --- |
| 新建 | Tạo |
| 发起 | Khởi chạy |
| 保存 | Lưu |
| 发布 | Phát hành |
| 删除 | Xóa |
| 取消 | Hủy |
| 确认 | Xác nhận |
| 编辑 | Sửa |
| 搜索 | Tìm kiếm |

Các chuỗi còn lại dịch sát nghĩa, dùng bảng trên khi cùng khái niệm. Tên trường hoặc giá trị chèn vào câu lỗi giữ trong dấu “ ”. Mã lỗi Agent (`AGENT_OUTPUT_NOT_JSON` và các mã cùng kiểu) giữ nguyên. Tiêu đề và mô tả trong `errorPresentation.ts` dịch.

Câu dự phòng trong `http.ts`:

| Tiếng Trung | Tiếng Việt |
| --- | --- |
| 登录状态已失效 | Phiên đăng nhập đã hết hạn |
| 没有权限执行此操作 | Bạn không có quyền thực hiện thao tác này |
| 网络请求失败 | Yêu cầu mạng thất bại |
| 服务响应格式不正确 | Phản hồi của máy chủ không đúng định dạng |
| 操作失败 | Thao tác thất bại |
| 无法建立安全请求上下文 | Không tạo được ngữ cảnh yêu cầu an toàn |

## Kiểm tra

- Cập nhật test đơn vị đang chờ chữ giao diện tiếng Trung: `format.spec.ts`, `sharedPresentation.spec.ts`, `bpmn.spec.ts`, `startProcess.spec.ts`, `processDefinitionDomain.spec.ts`, `interaction.spec.ts`.
- Cập nhật assertion chữ giao diện trong `e2e/workflow.spec.ts`. Dữ liệu mock tiếng Trung giữ nguyên, assertion vẫn tìm đúng dữ liệu đó.
- Chạy test đơn vị của `workflow-agent-web`.
- Mở ứng dụng và đi qua đăng nhập, menu, một danh sách, định dạng ngày, và vài nhãn trên trình thiết kế.

Xong khi không còn chuỗi tiếng Trung hiển thị cho người dùng trong `workflow-agent-web`, ngày giờ đúng `DD-MM-YYYY HH:mm:ss`, và các test trên chạy qua.
