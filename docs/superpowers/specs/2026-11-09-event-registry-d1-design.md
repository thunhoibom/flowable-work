# Bước chờ và sự kiện: Đợt 1

## Mục tiêu

Người thiết kế đặt được bước chờ vào quy trình theo mô hình Events & Channels của Flowable Enterprise:

- **Bước chờ thủ công** (Receive Task): quy trình dừng lại cho tới khi người vận hành hoặc một hệ thống gọi "tiếp tục".
- **Chờ sự kiện**: Receive Event Task, Intermediate Event Registry Catch, Boundary Event Registry. Mỗi phần tử chọn một event model, khai báo correlation bằng biểu thức, và map trường của sự kiện vào biến.
- **Thư viện Sự kiện theo tenant**: event model và channel model, có nháp và phát hành, version theo cách của Flowable.
- **Channel REST inbound** (webhook) theo mẫu Enterprise. Có chống trùng và mã phản hồi rõ ràng.
- **Màn vận hành**: danh sách instance đang chờ, nút "Tiếp tục".

Căn cứ, luồng dữ liệu và danh sách rủi ro (R-xx) nằm trong `2026-11-09-receive-task-research.md`. Spec này chỉ viết phần thiết kế.

## Ngoài phạm vi Đợt 1

- Event Registry Start (Đ2).
- Channel Kafka, RabbitMQ, JMS (Đ3).
- Send Event Task, Send-and-Receive, outbound channel (Đ4).
- Content item, bảng đệm sự kiện đến sớm, trường lồng nhau, XML, cờ Meta.
- Nhật ký sự kiện nhận vào và màn "Nhật ký nhận" (quyết định 2026-11-09). Enterprise cũng không có tính năng này.
- Event key dạng biểu thức trên sơ đồ. Đ1 chỉ cho chọn một key cố định.

Publish guard **từ chối** các phần tử thuộc đợt sau nếu chúng xuất hiện trong XML: `startEvent` có `flowable:eventType`, `serviceTask flowable:type="send-event"`. Nhờ vậy không có tính năng nào chạy nửa vời.

## Tiền đề (phải xong trước lát 2)

1. **Xác minh RLS trên bảng Flowable (R-C1).** Phát hiện thêm khi đọc code:
   - `ProcessDeployer` ghi `TENANT_ID_ = tenant.tenantId()`.
   - Trong khi đó policy V31 so `tenant_id_ = app.tenant_code`.
   - Kết hợp với lỗi chữ hoa `'TENANT_ID_'` trong V31, nhiều khả năng policy **chưa từng được áp**. Nếu có áp, các truy vấn đã sai ngay từ bây giờ, trừ khi tenantId trùng tenantCode.
   - Cách kiểm tra: `SELECT relname, relrowsecurity FROM pg_class WHERE relname LIKE 'act_%';` cùng `SELECT tenant_id, tenant_code FROM workflow_tenant;`.
   - Sửa migration là một việc riêng, không nằm trong spec này. Spec này chỉ yêu cầu: mọi truy vấn của Đ1 đều tự lọc theo `tenantId`, không trông vào RLS.
2. **Bảng `FLW_EVENT_*` đã có trên mọi môi trường (R-G4).** Nếu chưa có thì thêm vào cách tạo schema Flowable hiện hành.
3. **Feature flag:** `workflow.event-registry.enabled` ở backend và `eventsEnabled` trong `product.ts`. Khi tắt thì ẩn menu, palette và các mục chờ sự kiện. Publish guard từ chối `flowable:eventType`. Receive Task thủ công không phụ thuộc flag này.

## Kết quả kiểm tra tiền đề

| # | Kiểm tra | Môi trường | Kết quả (2026-11-09) | Kết luận |
|---|---|---|---|---|
| 1 | RLS trên `act_ru_*` | local | Mọi bảng `act_ru_*` có `relrowsecurity = false`, `relforcerowsecurity = false` | R-C1 **xác nhận**: policy V31 chưa từng được áp. Cách ly tenant trên bảng Flowable chỉ dựa vào code. Không có truy vấn nào bị RLS chặn |
| 2 | tenant id và tenant code | local | `workflow_tenant`: `default/default`, `VETC_TNHH/VETC_TNHH`. `act_re_procdef.tenant_id_`: chỉ có `default` | Hiện tenant id **trùng** tenant code, nên dữ liệu Flowable khớp cả hai. Nhưng điều này chỉ do quy ước: `TenantManagementServiceImpl` nhận `tenantId` và `tenantCode` thành hai giá trị riêng, không có ràng buộc nào bắt chúng bằng nhau (V9 chỉ cấm sửa sau khi tạo) |
| 3 | Bảng `flw_event_*`, `flw_channel_definition` | local | Có đủ cả 4 bảng: `flw_event_deployment`, `flw_event_resource`, `flw_event_definition`, `flw_channel_definition` | Đạt. Lát 1 không bị chặn |

Mới kiểm tra trên **DB local**. Trước khi merge migration V40, phải chạy lại cả ba câu trên mọi môi trường dùng chung (dev, staging, production) và bổ sung kết quả vào bảng này.

Sửa V31 là một việc riêng, **không** thuộc Đ1. Khi làm thì:

- Policy phải so `tenant_id_` với `app.tenant_id`, không phải `app.tenant_code`, vì Flowable lưu `tenant.tenantId()`. Như vậy mới đúng cả khi sau này có tenant mà id khác code.
- Sửa lỗi chữ hoa: so `lower(column_name) = 'tenant_id_'`.
- Hoặc thêm ràng buộc `tenant_id = tenant_code` cho `workflow_tenant` nếu muốn giữ đúng quy ước này. Nên chốt với người quản lý tenant.

## Mức tương thích với Flowable Enterprise

| Hạng mục | Mức | Ghi chú |
|---|---|---|
| XML BPMN (`flowable:eventType`, `eventCorrelationParameter`, `eventOutParameter`, `asyncLeave`) | Đúng chuẩn | Tên theo `BpmnXMLConstants` 8.0.0. Hành vi lúc chạy là behavior gốc |
| JSON event model (`key`, `name`, `payload[]` với `type`, `correlationParameter`, `header`, `isFullPayload`) | Đúng chuẩn | Kiểu: `string`, `integer`, `long`, `double`, `boolean`, `json` |
| JSON channel model (`channelType: inbound`, `type: rest`, `deserializerType: json`, `channelEventKeyDetection`) | Đúng chuẩn | Loại `rest` được đăng ký vào `ChannelJsonConverter` |
| Endpoint webhook `/platform-api/channel-definitions/key/{channelKey}/events` | Đúng đường dẫn | Header HTTP trùng tên trường *Header* thì được map vào trường đó, như Enterprise |
| Quyền channel theo user và group | Tương đương | `userIds` là principalId (client_code của service token, hoặc subject của Keycloak). `groupIds` là mã role |
| Version mới nhất theo key | Đúng chuẩn | Khác Enterprise ở chỗ: deploy theo thư viện tenant, không theo App |
| Chống trùng, mã phản hồi, cấm sửa không tương thích | Mở rộng | Enterprise không đề cập. Mục đích là chặn R-A2, R-E2, R-E3 |
| Consumer xử lý lỗi từng subscription | Mở rộng | Lớp con của `BpmnEventRegistryEventConsumer`. Hành vi khớp giữ nguyên |
| Event key là biểu thức | Hẹp hơn | Đ1 chỉ cho key cố định để validate được lúc phát hành |

## Mô hình

### Event model

| Trường | Ràng buộc |
|---|---|
| `eventKey` | Bất biến. Gồm `[a-zA-Z][a-zA-Z0-9_-]{0,63}`, theo đúng quy tắc của process key. Duy nhất trong tenant |
| `name`, `description` | Tự do |
| `fields[]` | `name` (định danh, duy nhất), `type`, `correlationParameter`, `header`, `fullPayload` |

Quy tắc:

- Có ít nhất một trường `correlationParameter`.
- Trường correlation chỉ được có kiểu `string`, `integer` hoặc `long` (R-B3). Không cho `double` hay `json`.
- Mỗi event chỉ có tối đa 3 trường correlation (R-B4).
- `fullPayload` chỉ cho kiểu `json`, và chỉ một trường trong event có cờ này.

**Thay đổi sau khi đã phát hành (R-E2, R-E3).** Chỉ được:

- thêm trường **không** phải correlation;
- sửa `name`, `description`.

Đổi tên, đổi kiểu, xóa trường hoặc đổi cờ correlation thì phải tạo event key mới. API trả lỗi `EVENT_MODEL_INCOMPATIBLE_CHANGE` và liệt kê từng trường vi phạm.

### Channel model (REST inbound)

| Trường | Ràng buộc |
|---|---|
| `channelKey` | Bất biến, cùng quy tắc với event key. Tối đa 64 ký tự để `consumer_name` của `platform_inbox_event` (VARCHAR 160) chứa đủ `event-channel:` + tenant id + `:` + channel key |
| `name`, `description` | Tự do |
| `eventKeyDetection` | Một trong ba: `fixedValue` (một event cố định), `jsonField`, `jsonPointerExpression` |
| `allowedEventKeys[]` | Bắt buộc khi detection không phải `fixedValue`. Sự kiện có key ngoài danh sách bị từ chối |
| `security.userIds[]`, `security.groupIds[]` | Ít nhất một mục. Không có thì không ai gọi được |

`channelEventTenantIdDetection.fixedValue = tenantId` do hệ thống sinh khi deploy. Người dùng không sửa được trường này (Q2). Mọi event key mà channel tham chiếu phải đã phát hành trong tenant.

JSON sinh ra để deploy:

```json
{
  "key": "paymentWebhook",
  "category": "channel",
  "name": "Webhook thanh toán",
  "channelType": "inbound",
  "type": "rest",
  "deserializerType": "json",
  "channelEventKeyDetection": { "fixedValue": "paymentReceived" },
  "channelEventTenantIdDetection": { "fixedValue": "<tenantId>" },
  "allowedEventKeys": ["paymentReceived"],
  "security": { "userIds": ["erp-client"], "groupIds": [] }
}
```

`allowedEventKeys` và `security` là thuộc tính của `RestInboundChannelModel` (mục Engine).

### Bước chờ trên sơ đồ

| Phần tử | XML | Điều kiện |
|---|---|---|
| Bước chờ thủ công | `<receiveTask>` không có `flowable:eventType` và không có `workflow:agentTask` | Không có |
| Chờ sự kiện (task) | `<receiveTask>` + `flowable:eventType` + ≥1 `eventCorrelationParameter` + `eventOutParameter`* | Không có `workflow:agentTask` (R-H2) |
| Chờ sự kiện (intermediate) | `<intermediateCatchEvent>` **không có** event definition + các extension như trên | Theo parser 8.0.0 |
| Chờ sự kiện (boundary) | `<boundaryEvent>` không có event definition + các extension như trên, `cancelActivity` tùy chọn | Gắn lên task hoặc subprocess |

Mặc định cho mọi bước chờ mới là `flowable:asyncLeave="true"` (R-D1). Người thiết kế tắt được, nhưng sẽ thấy cảnh báo.

## Engine (workflow-boot, cấu hình Flowable)

Toàn bộ phần này đi qua các điểm mở rộng public của 8.0.0. Không vá class của Flowable.

1. **Loại channel `rest`:**
   - `RestInboundChannelModel extends InboundChannelModel`, có `allowedEventKeys` và `security`.
   - Đăng ký bằng `channelJsonConverter.addInboundChannelModelClass("rest", RestInboundChannelModel.class)`.
   - Thêm `RestInboundChannelModelProcessor` (một `ChannelModelProcessor`). Nó không mở listener nào, chỉ kiểm tra cấu hình. Pipeline vẫn do `InboundChannelModelProcessor` gốc dựng, vì class này nhận mọi `InboundChannelModel`.
2. **Consumer BPMN:**
   - `WorkflowBpmnEventRegistryEventConsumer extends BpmnEventRegistryEventConsumer`, gắn qua `processEngineConfiguration.setEventRegistryEventConsumer(...)`.
   - Override `handleEventSubscription`: bọc từng subscription trong try/catch, ghi kết quả (`processInstanceId`, `activityId`, thành công hay lỗi kèm mã) vào `InboundDispatchContext` của thread hiện tại (R-D2).
   - Logic tìm kiếm và khớp giữ nguyên.
3. **Không khớp:** `WorkflowNonMatchingEventConsumer` (một `EventRegistryNonMatchingEventConsumer`) đánh dấu `NO_MATCH` trên context.
4. **Change detection:**
   - Bật `flowable.eventregistry.enable-change-detection=true`.
   - Đặt `change-detection-delay=10s` để node khác đăng ký channel mới kịp thời (Q10).
   - Node chưa đăng ký channel thì endpoint trả `503 CHANNEL_NOT_READY`, bên gửi thử lại được.
5. **Tenant:**
   - Mọi lời gọi `EventRepositoryService` đều truyền `tenantId`.
   - Không bật `fallbackToDefaultTenant` (R-C4).

## Dữ liệu (migration V40)

Ba bảng mới, đều có RLS theo `app.tenant_id` giống mẫu V38. Không có bảng nhật ký sự kiện nhận vào.

```sql
-- Nháp event và channel. model_type ∈ ('EVENT','CHANNEL')
CREATE TABLE workflow_event_registry_draft (
    tenant_id      VARCHAR(64)  NOT NULL,
    model_type     VARCHAR(16)  NOT NULL,
    model_key      VARCHAR(128) NOT NULL,
    name           VARCHAR(256) NOT NULL,
    description    VARCHAR(1024),
    model_json     TEXT         NOT NULL,     -- dạng soạn thảo, chưa có trường hệ thống
    revision       INTEGER      NOT NULL,
    updated_by     VARCHAR(128) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_workflow_event_registry_draft PRIMARY KEY (tenant_id, model_type, model_key)
);

-- Mỗi lần phát hành thật sự tạo ra một version Flowable
CREATE TABLE workflow_event_registry_release (
    id                    BIGSERIAL    PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    model_type            VARCHAR(16)  NOT NULL,
    model_key             VARCHAR(128) NOT NULL,
    flowable_version      INTEGER      NOT NULL,
    flowable_definition_id VARCHAR(64) NOT NULL,
    flowable_deployment_id VARCHAR(64) NOT NULL,
    deployed_json_sha256  CHAR(64)     NOT NULL,
    action                VARCHAR(16)  NOT NULL,   -- PUBLISH | DELETE
    performed_by          VARCHAR(128) NOT NULL,
    performed_at          TIMESTAMPTZ  NOT NULL
);

-- Bước nào trong process definition nào dùng event nào. Ghi lúc deploy, giống workflow_call_reference
CREATE TABLE workflow_event_reference (
    tenant_id             VARCHAR(64)  NOT NULL,
    process_definition_id VARCHAR(64)  NOT NULL,
    activity_id           VARCHAR(255) NOT NULL,
    event_key             VARCHAR(128) NOT NULL,
    CONSTRAINT pk_workflow_event_reference PRIMARY KEY (tenant_id, process_definition_id, activity_id)
);

```

Bảng tham chiếu event dùng để chặn xóa và cảnh báo sửa đổi (R-E4).

**Chống trùng dùng bảng `platform_inbox_event` có sẵn** (V17, UNIQUE `(consumer_name, event_id)`):

- `consumer_name = 'event-channel:' + tenantId + ':' + channelKey`.
- `event_id` lấy từ header `Idempotency-Key`, bắt buộc phải là UUID.
- Bảng chỉ có `received_at` và `completed_at`. Không lưu correlation, payload hay kết quả, nên không có gì phải dọn theo kiểu nhật ký (R-F4).
- Chỉ những dòng đã `completed_at` mới được giữ lại, và giữ trong `workflow.event-registry.idempotency-retention` (mặc định 7 ngày) để chặn gửi lặp. Có job xóa dòng quá hạn của các consumer `event-channel:*`.

## Luồng phát hành

### Event model

1. `PUT /workflow/management/event-registry/events/{key}/draft`:
   - Kiểm tra `revision` như nháp quy trình.
   - Validate theo các quy tắc ở mục Mô hình.
2. `POST /workflow/management/event-registry/events/{key}/publish`:
   1. So bản nháp với bản đã phát hành gần nhất. Thay đổi không tương thích thì trả `EVENT_MODEL_INCOMPATIBLE_CHANGE`.
   2. Sinh JSON Flowable, tính `sha256`. Nếu giống bản đã deploy thì trả kết quả cũ và **không tăng version** (R-E1).
   3. `eventRepositoryService.createDeployment().name("event:" + key).addString(key + ".event", json).tenantId(tenantId).deploy()`.
   4. INSERT `workflow_event_registry_release` và ghi audit `EVENT_MODEL_PUBLISHED`.
3. `DELETE …/events/{key}` bị chặn với `EVENT_MODEL_IN_USE` khi:
   - còn `workflow_event_reference` trỏ tới **version mới nhất** của một quy trình;
   - hoặc còn `ACT_RU_EVENT_SUBSCR` với `EVENT_TYPE_ = key` của tenant;
   - hoặc còn channel đã phát hành tham chiếu key này.

   Khi được phép xóa thì xóa các deployment Flowable của key và ghi release `DELETE`.

Toàn bộ chạy trong một giao dịch Spring cùng `DataSource` với Flowable, theo cách `ProcessPublishServiceImpl` đang làm.

### Channel model

Giống event model, kèm thêm:

- Kiểm tra mọi event key được tham chiếu đã phát hành.
- Kiểm tra `security` có ít nhất một mục.

Channel không có quy tắc tương thích. Phát hành bản mới thì bản cũ ngừng nhận ngay.

### Quy trình (bổ sung vào `BpmnDraftInspector`)

Thêm `EventRegistryDeploymentGuard`, chạy cùng các guard hiện có. Mã lỗi là `INVALID_EVENT_BINDING`, thông báo tiếng Việt kèm id của phần tử:

- `flowable:eventType` là key cố định (không có `${`), event đã phát hành trong tenant, và flag đang bật.
- Có ≥1 `eventCorrelationParameter`. Mỗi `name` là một trường correlation của event. `value` khác rỗng (R-B1).
- Mỗi `eventOutParameter.source` là một trường của event. `target` là tên biến hợp lệ, không thuộc danh sách biến dành riêng của `ProcessVariablePolicy` (R-F3).
- `receiveTask` không vừa có `workflow:agentTask` vừa có `eventType` (R-H2).
- Không có `receiveTask` nào là đích của Event-based Gateway (R-H1).
- Phần tử của đợt sau thì từ chối với `EVENT_FEATURE_NOT_AVAILABLE`.

Khi deploy thành công, `ProcessDeployer` ghi `workflow_event_reference`, theo cách đang ghi `workflow_call_reference`.

## Luồng lúc chạy

### Nhận sự kiện qua webhook

```
POST /platform-api/channel-definitions/key/{channelKey}/events
Headers: X-Workflow-Token | Authorization + X-Tenant-Code,  Idempotency-Key: <UUID>, Content-Type: application/json
Body: JSON phẳng theo event model, ví dụ {"orderId":"SO-1","amount":120.5}
```

1. **Xác thực** bằng hai filter hiện có, giống hai cách của Enterprise:
   - **Token Keycloak** của service account (client credentials): `Authorization: Bearer` cùng `X-Tenant-Code`. Tương đương OAuth2 resource server của Enterprise. **Khuyến nghị** trong hướng dẫn tích hợp.
   - **Service token** `X-Workflow-Token` của `workflow_service_client`: đã có băm body và chống replay. Tenant phải thuộc `allowed_tenant_codes`.

   Tenant luôn lấy từ danh tính (R-C3, R-C4). Endpoint không `permitAll`, và được miễn CSRF như `/service-callbacks/**`.
2. **Channel:** `getChannelModelByKey(channelKey, tenantId)`. Không có thì `404 CHANNEL_NOT_FOUND`. Có model nhưng node chưa đăng ký pipeline thì `503 CHANNEL_NOT_READY`.
3. **Quyền:** principalId thuộc `security.userIds`, hoặc caller có role thuộc `security.groupIds`. Sai thì `403`.
4. **Giới hạn:** body tối đa `workflow.event-registry.max-body-bytes` (mặc định 256 KB), vượt thì `413`.

   Đ1 không có rate limit theo channel, vì Enterprise cũng không có. Tần suất gọi do API gateway giới hạn (R-F2). Bảo vệ phía thực thi theo kiểu `flowable.sandbox.rate-limit` của Enterprise sẽ làm thành một việc riêng nếu cần.
5. **Chống trùng:** thiếu `Idempotency-Key` hoặc không phải UUID thì `400`. Chạy `INSERT INTO platform_inbox_event … ON CONFLICT (consumer_name, event_id) DO NOTHING` trong giao dịch riêng. Nếu đã có dòng:
   - Có `completed_at` (lần trước đã khớp toàn bộ hoặc một phần): trả `200 {status: "DUPLICATE"}` kèm header `Idempotent-Replayed: true`, không xử lý lại. Danh sách instance của lần trước không được trả lại vì không có nơi lưu.
   - Chưa có `completed_at` và `received_at` chưa quá 60 giây: `409 EVENT_IN_PROGRESS`.
   - Chưa có `completed_at` và đã quá 60 giây: lần trước chết giữa chừng. Cập nhật `received_at` rồi xử lý lại.

   Sau khi xử lý:
   - Có ít nhất một subscription khớp thành công (`MATCHED`, `PARTIAL`): đặt `completed_at`.
   - Còn lại (`NO_MATCH`, `FAILED`, `REJECTED`): **xóa dòng**, để bên gửi thử lại được với cùng key.
6. **Gửi vào engine:** mở `InboundDispatchContext`, rồi gọi `eventRegistry.eventReceived(channelModel, inboundEvent)`. `inboundEvent` gồm body và các header HTTP, trừ `Authorization`, `Cookie`, `X-Workflow-Token`. Pipeline gốc deserialize, nhận diện key và tạo `EventInstance`. Đúng trước khi gửi tới consumer, kiểm tra:
   - key thuộc `allowedEventKeys`, nếu không thì `422 EVENT_NOT_ALLOWED`;
   - đủ trường correlation và không rỗng, nếu không thì `422 INVALID_CORRELATION` (R-B2). So với các trường tương quan của event model, vì trường thiếu trong body không xuất hiện trong `EventInstance`;
   - body không phải JSON thì `400 INVALID_EVENT`, pipeline không nhận ra sự kiện thì `422 EVENT_NOT_RECOGNIZED`.

   Lát 4 chạy pipeline của channel trực tiếp (`InboundEventProcessingPipeline#run`) để kiểm tra từng `EventInstance`, rồi mới gọi `eventRegistry.sendEventToConsumers`.
7. **Khớp và trigger:** consumer gốc tìm `ACT_RU_EVENT_SUBSCR` và trigger từng execution. Mỗi trigger là một command riêng. Lớp con ghi kết quả từng subscription.
8. **Kết quả:**

`status` dưới đây chỉ nằm trong body phản hồi, không được lưu lại.

| Kết quả trên context | `status` | HTTP | Bên gửi |
|---|---|---|---|
| ≥1 thành công, 0 lỗi | `MATCHED` | `200` `{status, matched:[{processInstanceId, activityId}]}` | Xong |
| ≥1 thành công, ≥1 lỗi | `PARTIAL` | `200` `{status, matched, failed:[{…, errorCode}]}` | Thử lại cùng key nếu muốn |
| Không có subscription | `NO_MATCH` | `409 NO_WAITING_INSTANCE` | Thử lại có backoff (R-A1) |
| Chỉ có lỗi, optimistic lock | `FAILED` | `409 CONCURRENT_UPDATE` | Thử lại |
| Chỉ có lỗi, loại khác | `FAILED` | `500 EVENT_PROCESSING_FAILED` | Thử lại, hoặc báo vận hành |
| Validate thất bại (bước 6) | `REJECTED` | `400` / `422` | Sửa dữ liệu |

`404` chỉ dùng cho channel không tồn tại, để bên gửi phân biệt được với trường hợp "chưa có instance chờ".

**Giới hạn đã biết:** `PARTIAL` được coi là đã xong, nên gửi lại cùng key sẽ nhận `DUPLICATE`. Các instance lỗi lần trước phải được gửi lại bằng **key mới**, và người vận hành xử lý theo log ứng dụng, vì Đ1 không có nhật ký. Spec chấp nhận giới hạn này và ghi vào hướng dẫn tích hợp.

Mỗi lần nhận sự kiện, ứng dụng ghi một dòng log có cấu trúc (tenant, channel, event key, idempotency key, status, số khớp, số lỗi), **không** có correlation hay payload. Đây là log ứng dụng thông thường, không phải bảng trong DB.

### Tiếp tục bước chờ thủ công

```
POST /workflow/management/process/instance/receive-task/trigger
{ "processInstanceId": "...", "activityId": "...", "executionId": null, "variables": {...}, "reason": "..." }
Role: SERVICE, WF_PLATFORM_ADMIN, WF_ADMIN, WF_OPERATOR
```

1. Lấy instance theo `processInstanceTenantId(tenantId)`. Không có thì `404`.
2. Đọc `BpmnModel` của definition. Phần tử phải là `receiveTask`, **không** có `workflow:agentTask` và **không** có `flowable:eventType`. Sai thì `409 NOT_MANUAL_WAIT` (R-F1).
3. Tìm execution đang hoạt động tại `activityId`. Không có thì `409 NOT_WAITING`. Có nhiều (multi-instance) mà không truyền `executionId` thì `409 AMBIGUOUS_EXECUTION`.
4. `variables` qua `ProcessVariablePolicy`.
5. Chạy trong `ProcessInstanceTransactionExecutor` (Redis lock theo instance, như các thao tác vận hành khác), rồi `runtimeService.trigger(executionId, variables)`.
6. Ghi audit `RECEIVE_TASK_TRIGGERED` với lý do và tên các biến, không ghi giá trị.

### Xem các bước đang chờ

`GET /workflow/management/process/instance/{id}/waits` trả về:

- **Thủ công:** execution đang dừng tại `receiveTask` thủ công, gồm `activityId`, tên, `executionId`, `since`.
- **Sự kiện:** đọc `runtimeService.createEventSubscriptionQuery().processInstanceId(id)`, lấy các dòng có `EVENT_TYPE_` là event key. Trả `eventKey`, `activityId`, `created`, hạn chờ (`DUEDATE_` của timer boundary nếu có), và **giá trị correlation hiện tại**.

Engine chỉ lưu MD5 của khóa tương quan, không lưu giá trị gốc. Vì vậy giá trị correlation được tính lại từ biểu thức trên biến hiện tại của instance, và màn hình ghi rõ "giá trị hiện tại". Nếu MD5 tính lại khác `CONFIGURATION_` thì hiển thị cảnh báo "biến đã đổi sau khi bắt đầu chờ, sự kiện cần mang giá trị cũ".

`GET /workflow/management/event-registry/waits?eventKey=&page=` liệt kê subscription của tenant theo event, phục vụ màn "Đang chờ".

## Designer

- **Moddle (`flowableModdle.ts`):**
  - `flowable:EventType` (body), `flowable:EventCorrelationParameter` (`name`, `value`), `flowable:EventOutParameter` (`source`, `target`, `transient`).
  - Thuộc tính `asyncLeave` trên `bpmn:Activity`.
  - Round-trip qua `saveXML` phải giữ nguyên toàn bộ. Thêm bài test giống `subProcessRoundTrip.spec.ts`.
- **Palette:** "Bước chờ" (`receiveTask`). Ở replace menu của intermediate catch event, thêm "Chờ sự kiện". Ở boundary, thêm "Sự kiện".
- **Panel của bước chờ** có hai chế độ: *Chờ tiếp tục thủ công* và *Chờ sự kiện*. Agent task không có panel này.
  - **Chờ sự kiện**, ba khối:
    1. **Sự kiện:** chọn event đã phát hành, kèm mô tả và danh sách trường.
    2. **Tương quan:** mỗi trường correlation một dòng, nhập biểu thức (ví dụ `${orderId}`). Phải có ít nhất một dòng. Nếu bỏ trống một trường correlation của event thì cảnh báo (R-B4). Trong multi-instance, nếu biểu thức không dùng element variable thì cảnh báo (R-B5).
    3. **Dữ liệu nhận:** bảng trường → biến, có ô "chỉ dùng tạm" (`transient`).
  - **Hạn chờ:** nút "Thêm hạn chờ" tạo boundary timer. Bước chờ chưa có hạn chờ thì hiện cảnh báo, không chặn (R-G1).
  - **Rời bước bất đồng bộ** (`asyncLeave`): bật sẵn. Tắt thì hiện cảnh báo R-D1.
- Đổi chế độ thì xóa sạch extension của chế độ cũ. `stripMisplacedAgentBindings` và `withoutMisplacedAgentTasks` giữ nguyên hành vi.
- `bpmn.ts`:
  - `StartFrontierKinds` coi bước chờ sự kiện và bước chờ thủ công là một wait state, giống `hasAgentWait`.
  - Thêm `isEventWaitElement` và `isManualWaitElement`.

## Màn hình

Menu **Sự kiện** (`WF_EVENT`, order 36):

| Tab | Nội dung | Role |
|---|---|---|
| Sự kiện | Danh sách event model, editor trường, phát hành, lịch sử version, "đang được dùng ở" (`workflow_event_reference`) | Xem: designers + viewer. Sửa: `WF_PLATFORM_ADMIN`, `WF_ADMIN` |
| Kênh nhận | Danh sách channel REST, editor (detection, event cho phép, quyền), hiện URL webhook và ví dụ `curl` | Như trên |
| Đang chờ | Subscription theo event, link sang instance | + `WF_OPERATOR` |

**Chi tiết instance** có thêm khối "Đang chờ" (dữ liệu từ `/waits`). Bước chờ thủ công có nút **Tiếp tục**, mở modal nhập biến tùy chọn và lý do (bắt buộc). Bước chờ sự kiện chỉ hiển thị, không có nút.

## Lát triển khai

Mỗi lát phát hành được. Lát sau không đổi hành vi của lát trước.

**Trạng thái (2026-11-09):**

- Lát 1 và 2 đã code xong và qua kiểm tra tự động:
  - Backend `mvn verify` qua (`workflow-engine` 233 test, `workflow-boot` 54 test, SpotBugs 0, đạt ngưỡng độ phủ, Checkstyle 0 vi phạm).
  - Web type-check qua, 120 test qua.
- **Chưa commit.**
- **Chưa kiểm tra thủ công trên UI**, gồm Task 14 bước 7 và Task 15 bước 8 trong plan.

**Trạng thái lát 3–4 (2026-11-10):**

- Đã code xong và qua kiểm tra tự động:
  - Backend `mvn verify` qua: `workflow-engine` 272 test, `workflow-boot` 59 test, SpotBugs 0, đạt ngưỡng độ phủ. Checkstyle 0 vi phạm ở `workflow-engine`, `workflow-boot`, `platform-migrations`.
  - Web type-check qua, 138 test qua, oxlint và eslint sạch trên các đường dẫn đã sửa.
- **Chưa commit. Chưa kiểm tra thủ công trên stack local** (Task 14 trong plan lát 3–4), và chưa kiểm tra bằng token Keycloak thật.
- Một số điểm làm khác plan:
  - Trường tương quan thiếu trong body không có trong `EventInstance`, nên bộ nhận so với các trường tương quan của event model.
  - Thêm quy tắc: trường lấy từ header không được có kiểu `json`. Header được chuyển cho Flowable dưới dạng byte để Flowable đổi sang đúng kiểu của trường.
  - Body sai JSON trả `400 INVALID_EVENT`; lỗi khi chạy pipeline trả `422 EVENT_NOT_RECOGNIZED`.
  - `Idempotency-Key` phải là UUID dạng chuẩn.
  - Kênh `FIXED` có giá trị trống được coi là không nhận sự kiện nào.
  - Các test Postgres của Task 4, 11 và 12 gộp vào `EventRegistryPersistenceIntegrationTest`. Các trường hợp chi tiết của webhook nằm trong `InboundEventReceiverImplTest`, chạy trên engine in-memory.
- Còn mở:
  - Job dọn khóa `InboundIdempotencyJanitor` dùng `@Scheduled`. Trong ứng dụng, `@EnableScheduling` hiện chỉ bật khi Agent worker bật, giống `ServiceCallbackService`.
  - Chưa xác minh service account Keycloak đã có trong `auth_user_tenant`.

Phát hiện khi làm, đã xử lý:

1. Consumer BPMN tạo trước khi engine khởi tạo thì `commandExecutor` bị `null`, nên phải lấy lại khi dùng lần đầu.
2. Bật change detection làm ứng dụng **không khởi động được**. Executor mặc định của Flowable cần đúng một bean `TaskScheduler`, trong khi ứng dụng có hai. Đã xử lý bằng một bean `EventRegistryChangeDetectionExecutor` riêng.
3. Designer âm thầm xóa mọi phần tử `flowable:*` chưa khai báo trong moddle. Vì vậy một bước chờ sự kiện import vào sẽ thành bước chờ thủ công. Đã xử lý bằng cách khai báo `EventType`, `EventCorrelationParameter`, `EventOutParameter` ngay từ lát 2.

Lỗi có sẵn, không thuộc Đ1, nhưng đang chặn `mvn verify` và `npm run lint`:

- Checkstyle `auth-engine`: logger tên `log` trong `EimSitemapProvider`, `KeycloakIdentityProvider`, `KeycloakJwtVerifier`.
- JaCoCo `service-registry`: độ phủ nhánh 0.57, dưới ngưỡng 0.70.
- oxlint/eslint web: các lỗi ở `httpSession.spec.ts`, `ServiceCatalogView.spec.ts`, `e2e/workflow.spec.ts`.
- Test `enablesForcedRowLevelSecurityForFlowableTenantTables` luôn qua mà không kiểm tra gì, vì DB của test không có bảng `act_*`. Đây là lý do lỗi R-C1 của V31 không bị phát hiện.

**Trạng thái lát 5–6 (2026-11-10):**

- Đã code xong và qua kiểm tra tự động:
  - Backend `mvn verify` qua: `workflow-engine` 305 test, `workflow-boot` 60 test, SpotBugs 0. Checkstyle 0 vi phạm ở `workflow-engine`, `workflow-boot`, `platform-migrations`. Cảnh báo JaCoCo có sẵn của `service-registry` vẫn còn.
  - Web type-check qua, 166 test qua, oxlint và eslint sạch trên các đường dẫn đã sửa.
- **Chưa commit. Chưa kiểm tra thủ công trên stack local** (Task 14 trong plan lát 5–6).
- Một số điểm làm khác plan: xem "Quyết định khi làm lát 5–6" ở cuối tài liệu. Thêm nữa:
  - Cổng rẽ một nhánh có điều kiện bị Flowable từ chối lúc deploy, nên test R-D2 dùng hai nhánh có điều kiện, nhánh thứ hai luôn sai.
  - Bước chờ thủ công cũng trả `dueDate` khi có timer biên.
  - Khối "Đang chờ" của công việc chuyển từ bảng sang danh sách, để hiện được bảng trường tương quan.

1. **Nền engine:** tiền đề 1–3, các điểm mở rộng ở mục Engine (consumer, non-matching, loại `rest`, change detection), migration V40. Chưa có UI.
2. **Bước chờ thủ công:** palette, panel chế độ thủ công, `asyncLeave`, nút "Thêm hạn chờ", API trigger có whitelist, khối "Đang chờ" kèm nút Tiếp tục.
3. **Thư viện event model:** API nháp và phát hành, quy tắc tương thích, tab Sự kiện.
4. **Channel REST:** API channel, tab Kênh nhận, endpoint webhook, chống trùng qua `platform_inbox_event`, job xóa khóa quá hạn.
5. **Chờ sự kiện trên sơ đồ:** moddle, panel chế độ sự kiện cho receive task, intermediate và boundary, `EventRegistryDeploymentGuard`, `workflow_event_reference`.
6. **Vận hành:** tab Đang chờ, cảnh báo correlation đổi giá trị.

Lát 2 không phụ thuộc lát 3–4, có thể làm song song.

## Hướng dẫn tích hợp webhook

Dành cho hệ thống bên ngoài (ERP, cổng thanh toán…) gửi sự kiện vào một kênh REST đã phát hành.

**Địa chỉ:** `POST {gốc API}/platform-api/channel-definitions/key/{channelKey}/events`. Màn hình "Sự kiện → Kênh nhận" hiện sẵn địa chỉ và lệnh `curl` mẫu.

**Xác thực**, chọn một trong hai:

1. **Service account Keycloak (khuyến nghị).** Client credentials trên realm `rsa`, gửi `Authorization: Bearer <access token>` và `X-Tenant-Code: <mã tổ chức>`. Tài khoản phải là thành viên của tổ chức đó. Không cần CSRF.
2. **Service token** `X-Workflow-Token` của `workflow_service_client`. Tổ chức phải thuộc `allowed_tenant_codes`, và `allowed_paths` phải gồm `/platform-api/channel-definitions/**`. Mặc định không mở sẵn đường dẫn này.

Danh tính đó còn phải được kênh cho phép: mã người dùng/client thuộc "Người được gửi", hoặc có vai trò thuộc "Vai trò được gửi". Tổ chức luôn lấy từ danh tính, không bao giờ từ body.

**Header bắt buộc:**

- `Content-Type: application/json`.
- `Idempotency-Key: <UUID>`. Mỗi sự kiện một UUID mới. Chỉ dùng lại UUID khi **gửi lại đúng sự kiện đó** sau lỗi mạng hoặc mã có thể thử lại. Sau `PARTIAL`, các instance lỗi phải gửi bằng **UUID mới**, vì UUID cũ đã được đánh dấu xong. UUID đã xong bị chặn trùng trong `workflow.event-registry.idempotency-retention` (mặc định 7 ngày).

**Body:** JSON theo event model, tối đa `workflow.event-registry.max-body-bytes` (mặc định 262144 byte). Trường tương quan phải có và không rỗng. Trường đánh dấu "Lấy từ header" đọc từ header HTTP cùng tên. `Authorization`, `Cookie`, `X-Workflow-Token` không bao giờ vào sự kiện.

**Mã phản hồi** (body luôn là `{code, message, data, traceId}`):

| HTTP | `code` / `data.status` | Ý nghĩa | Bên gửi nên |
|---|---|---|---|
| 200 | `MATCHED` | Mọi công việc khớp đã được tiếp tục | Xong |
| 200 | `PARTIAL` | Một phần khớp lỗi, xem `data.failed` | Gửi lại phần lỗi bằng UUID mới nếu cần |
| 200 | `DUPLICATE` (header `Idempotent-Replayed: true`) | UUID này đã xử lý xong trước đó | Xong |
| 400 | `INVALID_EVENT` | Body trống hoặc không phải JSON | Sửa dữ liệu |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | Thiếu `Idempotency-Key` hoặc không phải UUID | Sửa header |
| 403 | `CHANNEL_FORBIDDEN` | Danh tính không được kênh cho phép | Cấu hình kênh |
| 404 | `CHANNEL_NOT_FOUND` | Không có kênh này trong tổ chức, hoặc tính năng đang tắt | Kiểm tra mã kênh |
| 409 | `NO_WAITING_INSTANCE` | Chưa có công việc nào đang chờ sự kiện này | **Thử lại có backoff** |
| 409 | `CONCURRENT_UPDATE` | Công việc đang được cập nhật đồng thời | **Thử lại có backoff**, cùng UUID |
| 409 | `EVENT_IN_PROGRESS` | Cùng UUID đang được xử lý (tối đa 60 giây) | Chờ rồi thử lại cùng UUID |
| 413 | `EVENT_TOO_LARGE` | Body vượt giới hạn | Gửi ít dữ liệu hơn |
| 415 | | Thiếu `Content-Type: application/json` | Sửa header |
| 422 | `EVENT_NOT_RECOGNIZED` | Không nhận ra sự kiện, ví dụ trường nhận diện sai | Sửa dữ liệu |
| 422 | `EVENT_NOT_ALLOWED` | Kênh không nhận sự kiện này | Sửa dữ liệu hoặc kênh |
| 422 | `INVALID_CORRELATION` | Thiếu hoặc rỗng trường tương quan | Sửa dữ liệu |
| 500 | `EVENT_PROCESSING_FAILED` | Tiếp tục công việc bị lỗi | Thử lại sau, báo vận hành nếu lặp lại |
| 503 | `CHANNEL_NOT_READY` | Kênh vừa phát hành, node này chưa nạp xong (tối đa ~10 giây) | **Thử lại có backoff** |

Mọi mã khác `200` đều giải phóng UUID, nên thử lại cùng UUID là an toàn. Backoff gợi ý: 1 s, 2 s, 4 s… tối đa khoảng 5 phút cho `NO_WAITING_INSTANCE` (sự kiện có thể đến trước khi quy trình tới bước chờ, Đ1 không đệm sự kiện đến sớm).

### Bước chờ sẽ nhận sự kiện nào

Một sự kiện chỉ tiếp tục những bước đang chờ đúng **mã sự kiện** đó, trong cùng tổ chức, và có **cùng giá trị ở mọi trường tương quan** mà bước khai báo. Các phần tử BPMN có thể chờ sự kiện:

- **Bước chờ** (`receiveTask`) ở chế độ "Chờ sự kiện". Có thể gắn hạn chờ bằng sự kiện hẹn giờ trên biên.
- **Sự kiện chờ** (`intermediateCatchEvent` không có event definition). Muốn có hạn chờ, đặt nó sau cổng rẽ theo sự kiện, cùng một sự kiện hẹn giờ.
- **Sự kiện gắn biên** (`boundaryEvent` không có event definition) trên một task hoặc quy trình con, kiểu ngắt hoặc không ngắt.

Giá trị tương quan được chốt **lúc bước bắt đầu chờ** (R-B2). Nếu biến của công việc đổi sau đó, sự kiện vẫn phải mang **giá trị cũ** mới khớp. Màn "Đang chờ" của công việc hiện giá trị hiện tại và cảnh báo khi giá trị đã đổi. So khớp dùng dạng chuỗi, nên `"123"` trong body khớp biến số `123`.

Nếu nhiều công việc chờ cùng giá trị, một sự kiện tiếp tục tất cả. Công việc nào lỗi thì nằm trong `data.failed`, còn các công việc khác vẫn được tiếp tục (`PARTIAL`).

## Kiểm tra

Mỗi lát có ba lớp: test engine với Flowable thật (Testcontainers PostgreSQL, như các integration test hiện có), test API, và test UI (spec Vitest).

Các bài sau luôn phải đúng từ lát tương ứng:

- **Lát 2:**
  - Trigger thủ công trên Agent task, Receive Event Task hoặc service task callback đều bị từ chối với `NOT_MANUAL_WAIT`.
  - Multi-instance mà thiếu `executionId` thì trả `AMBIGUOUS_EXECUTION`.
  - Có `asyncLeave` thì lỗi ở bước sau không rollback trigger.
- **Lát 3:**
  - Phát hành hai lần cùng nội dung thì `FLW_EVENT_DEFINITION.VERSION_` không đổi.
  - Đổi kiểu hoặc xóa trường đã phát hành thì trả `EVENT_MODEL_INCOMPATIBLE_CHANGE`.
  - Xóa event đang có subscription thì trả `EVENT_MODEL_IN_USE`.
- **Lát 4:**
  - Sai tenant hoặc principal không có trong `security` thì trả `403`.
  - Gửi cùng `Idempotency-Key` sau khi đã `MATCHED` thì nhận `DUPLICATE` và không trigger, kể cả khi instance đã quay lại bước chờ (R-A2).
  - Sự kiện đến sớm thì nhận `409 NO_WAITING_INSTANCE`, và dòng trong `platform_inbox_event` bị xóa. Gửi lại cùng key sau khi instance tới bước chờ thì `MATCHED`.
  - Thiếu `Idempotency-Key` hoặc key không phải UUID thì `400`.
  - Không có bảng nào lưu correlation hay payload của sự kiện.
  - Body thiếu correlation hoặc có correlation rỗng thì `422`.
  - Không có header `Authorization`, `Cookie` nào đi vào event.
- **Lát 5:**
  - Round-trip XML giữ nguyên extension.
  - Guard từ chối: bước chờ không có correlation, `source` không thuộc event, có cả `agentTask` và `eventType`, receive task sau Event-based Gateway, event start hoặc send-event.
  - Có hai instance chờ cùng event với correlation khác nhau, một sự kiện chỉ trigger đúng một instance.
  - `"123"` và `123` cho trường `string` khớp nhau. `null` bị từ chối.
  - Một subscription lỗi không chặn các subscription khác, kết quả là `PARTIAL` (R-D2).
  - Timer chạy trước thì sự kiện đến sau nhận `NO_MATCH`.
  - Boundary event registry kiểu interrupting hủy được task đang chạy.
- **Lát 6:** biến correlation đổi sau khi bắt đầu chờ thì màn hình hiện cảnh báo.

## Quyết định đã chốt (2026-11-09)

Đối chiếu với Flowable Enterprise:

1. **Nhật ký nhận: chưa làm** (cập nhật 2026-11-09):
   - Enterprise cũng không có nhật ký hay màn xem sự kiện đã nhận.
   - Chống trùng vẫn giữ, dùng `platform_inbox_event` có sẵn, chỉ lưu khóa và thời điểm.
   - Tra soát dựa vào log ứng dụng có cấu trúc.
   - Nếu sau này cần lưu nội dung thì làm theo kiểu Enterprise: tùy chọn trên từng bước chờ, lưu vào instance.
2. **Rate limit:** Enterprise không giới hạn REST hay channel. Đ1 không làm rate limit, để API gateway lo.
3. **Xác thực webhook:** hỗ trợ cả token Keycloak (khuyến nghị, tương đương OAuth2 của Enterprise) lẫn `X-Workflow-Token`, ngay từ Đ1.

## Quyết định khi làm lát 5–6 (2026-11-10)

1. **`CorrelationProbe` chỉ tính lại đường dẫn biến đơn giản.** Một biểu thức tương quan chỉ được tính lại khi có dạng `${a}` hoặc `${a.b.c}` và `a` là biến của execution. Biểu thức khác có thể gọi bean, nên màn hình ghi "không tính lại" và bỏ qua việc so khớp với khóa đã lưu. Giá trị hiện tại trả về cho người có quyền đọc, nhưng không bao giờ ghi vào log.
2. **Boundary Event Registry trên quy trình con nhúng được chấp nhận**, cùng với timer và error. Các event definition khác trên quy trình con vẫn bị từ chối.
3. **Màn "Sự kiện" mở cho mọi vai trò đọc** (`WF_OPERATOR`, `WF_VIEWER`) để họ dùng tab "Đang chờ". Nút tạo, lưu, phát hành, xóa chỉ hiện cho người thiết kế. EIM gán `WF_EVENT` cho cả bốn vai trò.
4. **Sự kiện chờ (intermediate) lấy hạn chờ qua cổng rẽ theo sự kiện**, cùng một sự kiện hẹn giờ. Designer ghi gợi ý này, không gắn timer lên intermediate.
5. **Bước lặp (multi-instance):** guard không bắt buộc giá trị tương quan dùng biến phần tử. Designer chỉ cảnh báo (R-B5).
6. **Intermediate catch "none" không có `flowable:eventType`:** Flowable coi đó là một trạng thái chờ thường, tiếp tục bằng trigger. Đ1 không từ chối và cũng không đưa vào palette.
7. **Mã sự kiện trên sơ đồ** được Flowable đọc sau khi `trim()`. Guard kiểm tra mã đã `trim()`.
8. **`latestProcessDefinitionsUsing` đếm số bản quy trình**, không đếm số bước. Một bản có hai bước cùng chờ một sự kiện tính là một.
