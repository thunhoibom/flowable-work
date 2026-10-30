# Service Registry

## Mục tiêu

Thêm cách gọi HTTP từ BPMN giống Service Registry của Flowable Work.

Người dùng tạo một service và các operation (method, URL, auth, body). Process chỉ chọn `serviceKey` và `operationKey`, rồi map biến. Publish lại service thì lần chạy sau dùng cấu hình mới mà không sửa sơ đồ.

Node này gọi API xác định. Nó không đi qua Agent.

## Phạm vi bản đầu

Có:

- Service catalog: tạo service, thêm operation, sửa nháp, Publish.
- Method: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.
- Auth: None, Bearer tĩnh, Basic, hoặc API key trên một header.
- Body và response JSON.
- Map input từ biến hoặc biểu thức process vào path, query, header, hoặc field JSON body.
- Map output từ field JSON response ra biến process.
- URL có thể chứa placeholder, ví dụ `/orders/${orderId}`.
- Timeout cấu hình trên operation.
- Lỗi HTTP, timeout, và mất kết nối trở thành `BpmnError` để error boundary bắt được.
- Panel trên Service Task: chọn service và operation đang publish, chỉnh mapping. Không có ô URL.

Không có:

- Import OpenAPI.
- Gọi qua Agent hoặc MCP.
- Multipart, OAuth2, retry tự động.
- Dán URL trực tiếp lên node BPMN.
- Khóa một version cũ trong BPMN.

## Kiến trúc

Ba phần:

1. Catalog lưu chi tiết kỹ thuật và snapshot đã publish.
2. BPMN chỉ giữ key và mapping.
3. Lúc chạy, delegate đọc snapshot publish mới nhất và gọi HTTP.

Module:

| Thành phần | Việc |
|---|---|
| `service-registry` | Lưu service, operation, snapshot; thực thi HTTP |
| `workflow-engine` | Service Task gọi port `invoke(serviceKey, operationKey, variables, mappings)`. Không chứa URL hay HTTP client |
| `workflow-boot` | Nối port với `service-registry` |
| `workflow-agent-web` | Màn catalog và panel trên node |
| `platform-migrations` | Bảng catalog |

`workflow-engine` phụ thuộc port, không phụ thuộc client HTTP. Binding trên BPMN dùng extension trong namespace `http://workflow-agent.local/bpmn`, cùng kiểu với binding hiện có.

Bảng mới đi theo cách lưu trữ hiện có của ứng dụng. Spec này không thêm mô hình tenant riêng và không thêm đường Agent.

## Dữ liệu

Service:

- `serviceKey` duy nhất, tên.

Operation nháp, thuộc một service:

- `operationKey`, duy nhất trong service.
- Method.
- URL.
- Auth: loại, tên header nếu là API key, và secret. Secret mã hóa khi lưu.
- Header bổ sung và body JSON mẫu.
- Timeout.
- Danh sách mã HTTP thành lỗi, ví dụ `400`, `409`, `5XX`.
- Một mã `BpmnError` cho các mã HTTP đó. Mặc định `HTTP_ERROR`.

Publication:

- Snapshot bất biến của mọi operation lúc Publish.
- Service trỏ tới snapshot mới nhất.
- Runtime chỉ đọc snapshot đó.
- Sửa nháp không đổi process cho đến lần Publish tiếp theo.

Trên Service Task:

- `serviceKey`
- `operationKey`
- Input: tham số operation ← biến hoặc biểu thức process.
- Output: field JSON response → biến process.

URL, method, auth và secret không nằm trên sơ đồ.

## Luồng chạy

1. Process tới Service Task.
2. Delegate đọc key và mapping trên node.
3. Không có snapshot publish, hoặc không có operation trong snapshot: ném `BpmnError` `SERVICE_NOT_PUBLISHED`.
4. Điền request từ biến process và gọi HTTP.
5. Response `2xx`: ghi output vào biến, đi tiếp.
6. Status khớp danh sách lỗi của operation: ném `BpmnError` với mã đã cấu hình, mặc định `HTTP_ERROR`.
7. Status khác ngoài `2xx`: ném `BpmnError` `HTTP_ERROR`.
8. Timeout hoặc không kết nối được: ném `BpmnError` `SERVICE_UNAVAILABLE`.

Secret không ghi vào biến process và không ghi vào log. API catalog không trả lại secret đầy đủ sau khi lưu.

## Kiểm tra

Luồng chính:

1. Tạo service `crm`, operation `createLead` (`POST` JSON, Bearer).
2. Publish.
3. BPMN: Start → Service Task `crm` / `createLead` → End, với error boundary. Map `customerName` vào body và `id` trong response ra `leadId`.
4. Start với `customerName`. API `201`: process đi tiếp và `leadId` có giá trị.
5. API `409` nằm trong danh sách lỗi: token vào error boundary, không đi nhánh thành công.
6. Sửa URL ở nháp rồi Publish. Lần start sau gọi URL mới. BPMN không đổi.

Test tự động:

- Chưa publish thì `SERVICE_NOT_PUBLISHED`.
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE` gửi đúng method.
- None, Bearer, Basic và API key gửi đúng header, không lộ secret ở biến process hay log.
- Timeout thành `SERVICE_UNAVAILABLE`.
- Status không phải `2xx` và không nằm trong danh sách lỗi thành `HTTP_ERROR`.
