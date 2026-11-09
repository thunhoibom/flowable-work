# Nghiên cứu: Receive Task theo Flowable Enterprise

## Mục tiêu

Người thiết kế đặt được một bước "chờ tín hiệu bên ngoài" vào quy trình, ở mức tương đương Flowable Design/Work:

- Chờ một hệ thống ngoài hoặc người vận hành bấm "tiếp tục" (Receive Task thường).
- Chờ một sự kiện nghiệp vụ có kiểu, lọc đúng instance bằng khóa tương quan, và đưa dữ liệu của sự kiện vào biến quy trình (Receive Event Task).
- Gắn hạn chờ bằng timer boundary event, và chạy được trong multi-instance.

Tài liệu này ghi lại căn cứ, luồng dữ liệu theo bảng DB, rủi ro và hướng làm. Thiết kế chi tiết và kế hoạch sẽ viết riêng sau khi chốt phạm vi.

## 0. Phạm vi (đang thảo luận, cập nhật 2026-11-09)

Hướng chung: bám chuẩn Flowable Enterprise (Events & Channels), trên engine OSS 8.0.0.

**Đã chốt:**

| Hạng mục | Quyết định |
|---|---|
| Phần tử BPMN | Đầy đủ chiều nhận và gửi: Receive Task thường, Receive Event Task, Intermediate Event Registry Catch, Boundary Event Registry, Event Registry Start, Send Event Task, Send-and-Receive Event Task |
| Channel | REST inbound (adapter tự viết, theo mẫu `/channel-definitions/key/{channelKey}/events` của Enterprise), cùng Kafka, RabbitMQ, JMS (adapter OSS, cả inbound lẫn outbound) |
| Đóng gói | Event model và channel model là **thư viện theo tenant**, có draft/publish riêng. Deploy độc lập với quy trình. Tra cứu theo version mới nhất, như Enterprise |
| Mở rộng ngoài chuẩn | Chống trùng theo `eventId` (dùng `platform_inbox_event` có sẵn), mã phản hồi rõ ràng (REST), màn hình "đang chờ sự kiện". **Chưa làm nhật ký inbound** (cập nhật 2026-11-09). Các mục bên dưới có nhắc tới `workflow_inbound_event` là phân tích ban đầu; thiết kế chốt nằm ở `2026-11-09-event-registry-d1-design.md` |
| Không làm | Bảng đệm sự kiện đến sớm, content item, Email/SQS/SNS/Camel, App |

**Căn cứ bổ sung từ source 8.0.0:**

- `SendEventTaskActivityBehavior`: mặc định **không** gửi đồng bộ mà tạo job `AsyncSendEventJobHandler`, nên event chỉ đi ra sau khi giao dịch commit. Với `triggerable`, engine tạo subscription chờ phản hồi **trước** khi gửi.
- Có `JsonField`/`JsonPointer`/`Xpath` key detector và `JsonPointerBasedInboundEventTenantDetector`.
- Có `EventRegistryChangeDetectionManager` để đăng ký channel trên các node khác của cluster.

**Còn phải chốt:** xem các câu hỏi mở ở mục 6.

## 1. Flowable Enterprise có những gì

Nguồn là trang tham chiếu của Flowable Design (React modeler). Các trang này chỉ liệt kê thuộc tính trên giao diện, không có tên attribute XML hay API.

| Phần tử | Cách tiếp tục | Thuộc tính riêng đáng chú ý |
|---|---|---|
| **Receive Task** | Hệ thống ngoài gọi REST hoặc code gọi trigger. Tài liệu ghi: *"consider using the Receive Event Task instead"* | Không có thuộc tính riêng. Có Multi-instance, Skip expression, Async/Exclusive, Leave async, Execution listeners |
| **Receive Event Task** | Event Registry: *"creates a subscription, which will then be triggered by next event received"*, lọc bằng correlation parameter | Inbound event (event model), Inbound channel, các tùy chọn lưu content item. Multi-instance: *"an event is expected to arrive for each element of that collection"* |
| **Send and Receive Event Task** | Gửi event ra, rồi chờ event phản hồi | Outbound event và channel, *Send on internal channel*, Inbound event và channel |
| **Intermediate Event Registry Catch** | Giống Receive Event Task nhưng ở dạng event | Inbound event và channel. Có *payload mapping* đưa dữ liệu event vào biến |
| **Message Catching Intermediate Event** | BPMN message (`messageEventReceived`) | Message reference hoặc Message expression |

Những điểm rút ra:

- Enterprise coi **Receive Event Task** (dựa trên Event Registry) là cách chính. Receive Task thường chỉ dành cho trường hợp tự viết code.
- Tài liệu Enterprise không nói về timeout, cách lưu trữ hay giao dịch. Phần đó phải lấy từ source của engine (mục 2).
- Content item là tính năng của Flowable Work. Engine OSS không có.

## 2. Căn cứ Flowable 8.0.0 (đã đọc source jar và DDL PostgreSQL trong `~/.m2`)

### 2.1 Hành vi

`ReceiveTaskParseHandler`:

- `receiveTask` có `<flowable:eventType>` khác rỗng thì engine dùng `ReceiveEventTaskActivityBehavior`.
- Không có thì engine dùng `ReceiveTaskActivityBehavior`, chỉ chờ `RuntimeService.trigger`. `trigger()` gọi thẳng `leave()`.

`ReceiveEventTaskActivityBehavior`:

- `execute()`: tính `eventType`, là expression. Sau đó tạo một dòng `ACT_RU_EVENT_SUBSCR` có `CONFIGURATION_ = CorrelationUtil.getCorrelationKey(...)`.
- `trigger()`: lấy `EventInstance` từ transient variable `eventInstance`, map `eventOutParameter` → biến, xóa subscription cùng `eventType`, rồi `leave()`.

`BpmnEventRegistryEventConsumer` / `BaseEventRegistryEventConsumer`:

- Tính khóa cho **mọi tập con khác rỗng** của các correlation parameter có trong event, tức 2ⁿ−1 khóa.
- Câu truy vấn: `EVENT_TYPE_ = key AND SCOPE_TYPE_ = 'bpmn' AND (CONFIGURATION_ IS NULL OR CONFIGURATION_ IN (…)) AND tenant`.
- Lặp qua từng subscription và gọi `runtimeService.trigger(executionId, null, {eventInstance})`. Vòng lặp **không có try/catch**. Comment trong code ghi: *"executing them one per subscription. There is no overarching transaction"*.

`DefaultCorrelationKeyGenerator`:

- Sắp tên tham số theo thứ tự, nối thành `name=value.toString();` (null → chuỗi rỗng), rồi băm **MD5**.
- `DefaultCorrelationValueTransformer` giữ nguyên giá trị, không chuẩn hóa gì.

`EventDefinitionDeployer`: mỗi lần deployment mới chứa `.event` cùng key và tenant thì `VERSION_ = latest + 1`, kể cả khi nội dung không đổi.

### 2.2 Tên phần tử XML (`BpmnXMLConstants`)

| Hằng | Giá trị | Dùng cho |
|---|---|---|
| `ELEMENT_EVENT_TYPE` | `eventType` | Key của event definition (expression) |
| `ELEMENT_EVENT_CORRELATION_PARAMETER` | `eventCorrelationParameter` (`name`, `value`) | Khóa lọc. `value` là expression tính trên execution |
| `ELEMENT_EVENT_OUT_PARAMETER` | `eventOutParameter` (`source`, `target`, `transient`) | Payload → biến |
| `ELEMENT_EVENT_IN_PARAMETER` | `eventInParameter` | Biến → payload (khi gửi) |
| `ATTRIBUTE_ACTIVITY_ASYNCHRONOUS_LEAVE` | `asyncLeave` | Rời bước chờ trong job async |
| `ELEMENT_TRIGGER_EVENT_TYPE`, `ELEMENT_TRIGGER_EVENT_CORRELATION_PARAMETER`, `ATTRIBUTE_TRIGGERABLE` | `triggerEventType`, … | Send-and-Receive |

```xml
<receiveTask id="waitPayment" name="Chờ thanh toán" flowable:asyncLeave="true">
  <extensionElements>
    <flowable:eventType>paymentReceived</flowable:eventType>
    <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
    <flowable:eventOutParameter source="amount" target="paidAmount"/>
  </extensionElements>
</receiveTask>
<boundaryEvent id="paymentTimeout" attachedToRef="waitPayment" cancelActivity="true">
  <timerEventDefinition><timeDuration>${paymentTimeout}</timeDuration></timerEventDefinition>
</boundaryEvent>
```

```json
{
  "key": "paymentReceived",
  "name": "Đã nhận thanh toán",
  "correlationParameters": [{ "name": "orderId", "type": "string" }],
  "payload": [{ "name": "amount", "type": "double" }]
}
```

### 2.3 API

- `EventRegistry` gồm:
  - `eventReceived(InboundChannelModel, String|InboundEvent)`: đi qua pipeline của channel.
  - `sendEventToConsumers(EventRegistryEvent)`: đẩy thẳng `EventInstance`.
  - `generateKey(Map)`.
- `EventRepositoryService` dùng để deploy `.event` độc lập với tenant, và tra `getEventModelByKey(key, tenantId)`.
- `RuntimeService` gồm:
  - `trigger(executionId[, vars[, transientVars]])` và `triggerAsync(executionId[, vars])`.
  - `messageEventReceived(...)`, `signalEventReceived(...)`.

### 2.4 Hạ tầng

- Event Registry **bật mặc định** (`disableEventRegistry = false`). Starter đã kéo theo `flowable-event-registry` và `-configurator`.
- `EventDeployer` của configurator deploy được `.event`/`.channel` nằm chung deployment với BPMN. Việc này gây trôi version (mục 4, R-E1), nên không chọn cách đó.
- Không có REST endpoint inbound dùng chung ở mức engine. Có adapter JMS, Kafka và RabbitMQ.
- Boundary timer cần async executor. Dự án đã đặt `async-executor-activate: true`.
- Receive Task sau Event-based Gateway **không được hỗ trợ**.

## 3. Luồng dữ liệu theo bảng DB

### 3.1 Các bảng liên quan

**Bảng Flowable** (DDL `flowable.postgres.create.*.sql` 8.0.0):

| Bảng | Vai trò trong luồng | Cột chính |
|---|---|---|
| `ACT_RE_DEPLOYMENT`, `ACT_GE_BYTEARRAY`, `ACT_RE_PROCDEF` | Bản BPMN đã phát hành | `ID_`, `KEY_`, `VERSION_`, `TENANT_ID_` |
| `FLW_EVENT_DEPLOYMENT` | Deployment của Event Registry | `ID_`, `TENANT_ID_`, `PARENT_DEPLOYMENT_ID_` |
| `FLW_EVENT_RESOURCE` | Nội dung JSON `.event` | `DEPLOYMENT_ID_`, `RESOURCE_BYTES_` |
| `FLW_EVENT_DEFINITION` | Định nghĩa sự kiện có version | `KEY_`, `VERSION_`, `TENANT_ID_` (unique), `DEPLOYMENT_ID_` |
| `ACT_RU_EXECUTION` | Vị trí token đang chờ | `ID_`, `REV_`, `PROC_INST_ID_`, `PARENT_ID_`, `ACT_ID_`, `IS_ACTIVE_`, `EVT_SUBSCR_COUNT_`, `TENANT_ID_` |
| `ACT_RU_ACTINST` / `ACT_HI_ACTINST` | Bản ghi bước đang chạy / lịch sử | `ACT_ID_`, `ACT_TYPE_ = receiveTask`, `START_TIME_`, `END_TIME_`, `DELETE_REASON_` |
| `ACT_RU_EVENT_SUBSCR` | **Đăng ký chờ sự kiện** | `EVENT_TYPE_` (= event key), `EXECUTION_ID_`, `PROC_INST_ID_`, `ACTIVITY_ID_`, `CONFIGURATION_` (= MD5 khóa tương quan), `SCOPE_TYPE_ = bpmn`, `PROC_DEF_ID_`, `TENANT_ID_`, `CREATED_` |
| `ACT_RU_TIMER_JOB` → `ACT_RU_JOB` | Hạn chờ (boundary timer), asyncLeave | `ELEMENT_ID_`, `DUEDATE_`, `HANDLER_TYPE_`, `LOCK_OWNER_`, `RETRIES_` |
| `ACT_RU_VARIABLE` / `ACT_HI_VARINST` | Biến nhận từ payload | `NAME_`, `TYPE_`, `TEXT_`/`LONG_`/`DOUBLE_`, `PROC_INST_ID_` |

Index có sẵn trên `ACT_RU_EVENT_SUBSCR`: `CONFIGURATION_`, `EXECUTION_ID_`, `PROC_INST_ID_`, `(SCOPE_ID_, SCOPE_TYPE_)`. **Không có** index trên `EVENT_TYPE_`.

**Bảng của hệ thống** (đề xuất, theo mẫu V38 `workflow_process_draft` và RLS `app.tenant_id`):

| Bảng | Vai trò | Cột đề xuất |
|---|---|---|
| `workflow_event_definition` | Bản thiết kế sự kiện theo tenant (nguồn sự thật để soạn) | PK `(tenant_id, event_key)`, `name`, `correlation_json`, `payload_json`, `status` (DRAFT/PUBLISHED), `published_version`, `flowable_event_definition_id`, `updated_by`, `updated_at` |
| `workflow_inbound_event` | Nhật ký sự kiện nhận vào, chống trùng, phục vụ tra soát | PK `id`, `tenant_id`, `event_id` (UUID do bên gửi cấp, UNIQUE theo tenant), `event_key`, `correlation_json`, `payload_digest`, `status` (RECEIVED/MATCHED/NO_MATCH/FAILED), `matched_count`, `error_code`, `received_at`, `completed_at`, `client_id` |

Có thể dùng lại `platform_inbox_event` (V17, UNIQUE `consumer_name, event_id`) để chống trùng. Tuy vậy bảng đó không có trạng thái khớp và mã lỗi, nên vẫn cần `workflow_inbound_event` cho phần tra soát.

### 3.2 Luồng A: soạn và phát hành định nghĩa sự kiện

```
UI "Định nghĩa sự kiện"
  └─▶ UPSERT workflow_event_definition (status = DRAFT)
Phát hành
  └─▶ EventRepositoryService.createDeployment().addString(key + ".event", json).tenantId(t).deploy()
        ├─ INSERT FLW_EVENT_DEPLOYMENT (TENANT_ID_ = t, PARENT_DEPLOYMENT_ID_ = null)
        ├─ INSERT FLW_EVENT_RESOURCE (RESOURCE_BYTES_ = json)
        └─ INSERT FLW_EVENT_DEFINITION (KEY_, VERSION_ = latest + 1, TENANT_ID_ = t)
  └─▶ UPDATE workflow_event_definition SET status = PUBLISHED, published_version, flowable_event_definition_id
```

Deploy **độc lập** với BPMN, và chỉ khi nội dung đổi (so `payload_json`/`correlation_json` với bản đã phát hành). Mục đích là giữ một version cho mỗi thay đổi thật.

### 3.3 Luồng B: phát hành quy trình có Receive Task

```
workflow_process_draft (XML) ──validate──▶ đối chiếu với FLW_EVENT_DEFINITION (latest, tenant):
    - eventType tồn tại
    - mỗi eventCorrelationParameter.name ∈ correlationParameters
    - mỗi eventOutParameter.source ∈ payload
  └─▶ INSERT ACT_RE_DEPLOYMENT, ACT_GE_BYTEARRAY (bpmn), ACT_RE_PROCDEF
  └─▶ INSERT workflow_process_release
```

Engine chỉ tham chiếu sự kiện theo **key**, không theo version. Việc đối chiếu chỉ đúng tại lúc phát hành (xem R-E2).

### 3.4 Luồng C: instance đi tới bước chờ

Cùng một giao dịch với bước trước đó, ví dụ lúc start instance hoặc lúc hoàn thành user task trước đó:

| # | Bảng | Thao tác | Receive Task thường | Receive Event Task |
|---|---|---|---|---|
| 1 | `ACT_RU_EXECUTION` | UPDATE `ACT_ID_ = waitPayment`, `REV_ + 1` | ✓ | ✓ |
| 2 | `ACT_RU_ACTINST`, `ACT_HI_ACTINST` | INSERT `ACT_TYPE_ = receiveTask`, `END_TIME_ = null` | ✓ | ✓ |
| 3 | `ACT_RU_EXECUTION` | INSERT execution con cho boundary event (nếu có) | nếu có timer | nếu có timer |
| 4 | `ACT_RU_TIMER_JOB` | INSERT `ELEMENT_ID_ = paymentTimeout`, `DUEDATE_ = now + duration` | nếu có timer | nếu có timer |
| 5 | `ACT_RU_EVENT_SUBSCR` | INSERT `EVENT_TYPE_ = paymentReceived`, `CONFIGURATION_ = md5("orderId=SO-1;")`, `EXECUTION_ID_`, `ACTIVITY_ID_`, `TENANT_ID_` | — | ✓ |
| 6 | `ACT_RU_EXECUTION` | `EVT_SUBSCR_COUNT_ + 1` | — | ✓ |

Multi-instance song song: mỗi execution con có một dòng ở bước 1, 2 và 5. `value` của correlation được tính **trên từng execution con**, nên có thể dùng element variable.

### 3.5 Luồng D1: trigger thủ công (Receive Task thường)

```
POST /process-instances/{piId}/receive-tasks/{activityId}/trigger   {executionId?, variables}
  1. SELECT ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ? AND ACT_ID_ = ? AND IS_ACTIVE_ AND TENANT_ID_ = ?
     - 0 dòng → 409 NOT_WAITING
     - > 1 dòng (multi-instance) và không có executionId → 409 AMBIGUOUS
  2. Đọc model (ACT_RE_PROCDEF → BpmnModel cache): phần tử phải là receiveTask
     KHÔNG có workflow:agentTask và KHÔNG có flowable:eventType   ← bắt buộc, xem R-F1
  3. runtimeService.trigger(executionId, variables)
     ├─ INSERT/UPDATE ACT_RU_VARIABLE, INSERT ACT_HI_VARINST
     ├─ DELETE ACT_RU_TIMER_JOB của boundary, DELETE execution con của boundary
     ├─ UPDATE ACT_RU_ACTINST / ACT_HI_ACTINST SET END_TIME_
     └─ UPDATE ACT_RU_EXECUTION SET ACT_ID_ = <bước kế>, REV_ + 1  (kiểm tra REV_ → optimistic lock)
  4. INSERT bản ghi audit thao tác vận hành (bảng audit hiện có)
```

### 3.6 Luồng D2: nhận sự kiện (Receive Event Task)

```
POST /events/{eventKey}   Header: Idempotency-Key = eventId     Body: {correlation:{orderId:"SO-1"}, payload:{amount:120.5}}
  0. Xác thực client → xác định tenant t (KHÔNG lấy tenant từ body). Đặt app.tenant_code / app.tenant_id cho connection.
  1. INSERT workflow_inbound_event (tenant_id, event_id, status = RECEIVED)
       - vi phạm UNIQUE → trả lại kết quả lần trước (200 kèm status cũ), dừng
  2. getEventModelByKey(eventKey, t)   ← đọc FLW_EVENT_DEFINITION + FLW_EVENT_RESOURCE (có cache)
       - không có → 404 EVENT_NOT_DEFINED
     Ép kiểu correlation/payload theo model, loại field lạ, thiếu correlation → 400
  3. Dựng EventInstance, gọi eventRegistry.sendEventToConsumers(...)
       Consumer:
       a. SELECT ACT_RU_EVENT_SUBSCR
            WHERE EVENT_TYPE_ = 'paymentReceived' AND SCOPE_TYPE_ = 'bpmn'
              AND (CONFIGURATION_ IS NULL OR CONFIGURATION_ IN (md5 các tập con))
              AND TENANT_ID_ = t
       b. Với mỗi dòng → runtimeService.trigger(EXECUTION_ID_, null, {eventInstance})
            ├─ eventOutParameter → INSERT/UPDATE ACT_RU_VARIABLE (paidAmount), ACT_HI_VARINST
            ├─ DELETE ACT_RU_EVENT_SUBSCR (dòng này), EVT_SUBSCR_COUNT_ − 1
            ├─ DELETE ACT_RU_TIMER_JOB + execution con của boundary
            ├─ UPDATE ACT_RU_ACTINST / ACT_HI_ACTINST SET END_TIME_
            └─ asyncLeave = true → INSERT ACT_RU_JOB (tiếp tục ở job), COMMIT nhanh
               asyncLeave = false → chạy tiếp các bước sau ngay trong request này
  4. UPDATE workflow_inbound_event SET status = MATCHED | NO_MATCH, matched_count, completed_at
       - NO_MATCH → trả 404 NO_WAITING_INSTANCE để bên gửi thử lại có backoff
       - lỗi → status = FAILED, error_code, trả 409/500
```

### 3.7 Luồng E: hết hạn chờ (boundary timer)

```
Async executor: SELECT ACT_RU_TIMER_JOB WHERE DUEDATE_ <= now, khóa (LOCK_OWNER_) → chuyển sang ACT_RU_JOB → chạy:
  ├─ DELETE ACT_RU_EVENT_SUBSCR của execution receive task (khi cancelActivity = true)
  ├─ UPDATE ACT_RU_ACTINST / ACT_HI_ACTINST SET END_TIME_, DELETE_REASON_
  ├─ DELETE execution của receive task
  └─ token đi theo nhánh timeout
```

Sự kiện đến sau thời điểm này sẽ không còn subscription nào khớp, kết quả là `NO_MATCH`.

### 3.8 Luồng F: hủy hoặc xóa instance, gỡ deployment

- Xóa instance: DELETE toàn bộ `ACT_RU_EVENT_SUBSCR`, `ACT_RU_TIMER_JOB`, `ACT_RU_EXECUTION` của instance. History giữ `DELETE_REASON_`.
- Xóa deployment BPMN theo kiểu cascade: xóa instance đang chạy, kéo theo subscription.
- Nếu đã deploy `.event` chung với BPMN (không khuyến nghị), xóa deployment BPMN sẽ kéo theo `FLW_EVENT_DEPLOYMENT` con (`PARENT_DEPLOYMENT_ID_`).

## 4. Rủi ro

Mức: **Cao** là sai dữ liệu nghiệp vụ hoặc lộ dữ liệu; **TB** là gây lỗi vận hành; **Thấp** là cần lưu ý.

### A. Mất hoặc trùng sự kiện

| Mã | Rủi ro | Cơ chế (theo source/DDL) | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-A1 | Sự kiện đến **trước** khi instance tới bước chờ thì bị mất | Consumer chỉ tìm trong `ACT_RU_EVENT_SUBSCR`. Không có dòng nào thì bỏ qua, không lưu đệm | Cao | Trả `404 NO_WAITING_INSTANCE`, ghi `NO_MATCH` để bên gửi thử lại có backoff. Phải ghi rõ hợp đồng này cho bên tích hợp. Chỉ làm bảng đệm có TTL khi thật sự cần |
| R-A2 | Gửi lại cùng sự kiện, rơi vào **vòng lặp** của quy trình | Quy trình quay lại cùng receive task thì có subscription mới, và sự kiện cũ gửi lại sẽ khớp lần lặp sau | Cao | Bắt buộc `eventId`, UNIQUE `(tenant_id, event_id)` trong `workflow_inbound_event`. Trùng thì trả kết quả cũ |
| R-A3 | Sự kiện đến sau timeout | Timer đã xóa subscription | TB | Trả `NO_MATCH`, có nhật ký để tra soát |

### B. Khớp sai instance

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-B1 | Bước chờ **không khai báo correlation** thì mọi sự kiện cùng key trong tenant sẽ trigger **tất cả** instance đang chờ | Truy vấn có `CONFIGURATION_ IS NULL OR …` | Cao | Validate khi phát hành: Receive Event Task bắt buộc có ít nhất một correlation parameter |
| R-B2 | Giá trị correlation là null thì khóa thành `orderId=;` | `DefaultCorrelationKeyGenerator`: null → `""` | Cao | Engine không chặn được lúc tạo subscription. Hệ thống cần kiểm tra trước khi vào bước, ví dụ bằng execution listener `start` ném lỗi nếu giá trị rỗng. Endpoint từ chối correlation rỗng |
| R-B3 | Lệch định dạng giữa hai đầu: `1` và `1.0`, hoa/thường, khoảng trắng | Khóa = `value.toString()`, transformer mặc định không chuẩn hóa | Cao | Ép kiểu theo định nghĩa ở cả hai đầu. Kiểu `string` thì trim. Không dùng `double` cho correlation |
| R-B4 | **Khớp theo tập con**: sự kiện mang `{orderId, customerId}` khớp cả subscription chỉ lọc theo `customerId` | Consumer sinh khóa cho mọi tập con (2ⁿ−1) | TB | Chọn correlation đủ đặc trưng. Hướng dẫn người thiết kế. Giới hạn n ≤ 3 |
| R-B5 | Multi-instance song song cùng giá trị correlation thì một sự kiện trigger cả N nhánh | Mỗi nhánh có một subscription với cùng `CONFIGURATION_` | TB | Correlation nên dùng element variable. Cảnh báo trong designer |
| R-B6 | Va chạm MD5 | `CONFIGURATION_` là MD5 | Thấp | Bỏ qua |

### C. Đa tenant và RLS

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-C1 | **RLS của V31 có thể không áp vào bảng Flowable nào** | V31 lọc `information_schema.columns` theo `column_name = 'TENANT_ID_'` (chữ hoa). Trong khi đó DDL Flowable không quote tên, nên PostgreSQL lưu là `tenant_id_` (chữ thường) | Cao (cần xác minh) | Chạy `SELECT relname, relrowsecurity FROM pg_class WHERE relname LIKE 'act_ru_%'` trên DB thật. Nếu RLS chưa bật thì sửa migration (so khớp `lower(column_name)`) trong một việc riêng |
| R-C2 | Bảng `FLW_EVENT_*` nằm ngoài RLS | V31 chỉ lọc `act_%` | TB | Chỉ truy cập qua `EventRepositoryService` kèm `tenantId`. Cân nhắc thêm policy |
| R-C3 | Endpoint inbound không đặt ngữ cảnh tenant cho connection thì truy vấn subscription trả 0 dòng, và sự kiện bị `NO_MATCH` **âm thầm** | RLS deny-by-default dựa trên `app.tenant_code` (`TenantRlsDataSourcePostProcessor`) | Cao | Endpoint đặt tenant từ danh tính client. Có test tích hợp với RLS bật |
| R-C4 | Tenant lấy từ body thì có thể trigger chéo tenant | Truy vấn lọc theo `eventInstance.getTenantId()` | Cao | Tenant chỉ lấy từ xác thực. Tắt `fallbackToDefaultTenant` |
| R-C5 | Job async (asyncLeave, timer) chạy dưới quyền `system_worker`, bỏ qua lọc tenant | Policy cho phép `app.system_worker = 'true'` | Thấp | Đây là hành vi sẵn có của hệ thống, chỉ cần ghi nhận |

### D. Giao dịch và đồng thời

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-D1 | Trigger chạy tiếp **toàn bộ các bước sau** trong thread HTTP. Bước sau lỗi thì rollback cả trigger, bên gửi thấy 500, thử lại, và các tác dụng phụ không giao dịch (gọi HTTP, gửi mail) bị chạy lặp | Engine chạy đồng bộ tới wait state kế tiếp | Cao | Bật mặc định `flowable:asyncLeave="true"` trên bước chờ để trigger commit nhanh, phần còn lại chạy trong `ACT_RU_JOB` có retry |
| R-D2 | Một sự kiện khớp nhiều subscription mà subscription thứ k lỗi: các subscription trước đã commit, các subscription sau **không chạy** | Vòng lặp không có try/catch. Mỗi trigger là một command riêng | Cao | Endpoint tự lặp qua subscription (hoặc bọc consumer), bắt lỗi từng cái, ghi `matched_count`/`failed_count`. **Không** bọc toàn bộ trong `@Transactional` của Spring, nếu không sẽ thành all-or-nothing và đổi ngữ nghĩa |
| R-D3 | Sự kiện và timer cùng lúc, hoặc hai sự kiện cho cùng instance, gây optimistic lock | `ACT_RU_EXECUTION.REV_` | TB | Bên thua nhận `FlowableOptimisticLockingException` → trả 409 → thử lại sẽ ra `NO_MATCH`. Trigger thủ công dùng `triggerAsync` |
| R-D4 | Ghi `workflow_inbound_event` và trigger không cùng giao dịch, crash giữa chừng để lại `RECEIVED` treo | Hai bước tách nhau | TB | Job dọn `RECEIVED` quá hạn. Thử lại theo `eventId` |

### E. Version và triển khai

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-E1 | Đóng gói `.event` chung deployment BPMN thì **mỗi lần phát hành quy trình tăng version sự kiện**. Hai quy trình chứa hai bản khác nhau thì bản phát hành sau thắng cho cả tenant | `EventDefinitionDeployer` luôn `latest + 1`. Tra cứu dùng latest theo key | Cao | Deploy `.event` độc lập (luồng A), chỉ khi nội dung đổi |
| R-E2 | Sửa định nghĩa sự kiện (đổi tên hoặc xóa field payload) làm **instance đang chạy** map ra biến rỗng mà không báo lỗi | Subscription chỉ lưu key. `eventOutParameter` không tìm thấy field thì bỏ qua | Cao | Chỉ cho thay đổi tương thích ngược (thêm field). Đổi correlation hoặc xóa field thì bắt tạo key mới. Màn hình hiển thị các quy trình đang dùng key |
| R-E3 | Đổi danh sách correlation parameter làm subscription cũ không bao giờ khớp nữa | `CONFIGURATION_` đã được tính lúc vào bước | Cao | Giống R-E2: cấm đổi trên key đã phát hành |
| R-E4 | Xóa định nghĩa sự kiện khi còn instance chờ | Gọi `getEventModelByKey` sẽ ném lỗi | TB | Chặn xóa khi còn `ACT_RU_EVENT_SUBSCR` với `EVENT_TYPE_ = key` |

### F. Bảo mật

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-F1 | **API trigger thủ công vượt qua Agent task, Receive Event Task và service call** | `runtimeService.trigger` làm mọi `TriggerableActivityBehavior` đi tiếp. Với Receive Event Task thì subscription bị xóa và không map payload. Với Agent task thì quy trình đi tiếp khi agent chưa xong | Cao | Whitelist theo model: chỉ `receiveTask` không có `workflow:agentTask` và không có `eventType`. Kiểm tra quyền vận hành. Ghi audit |
| R-F2 | Endpoint inbound bị gọi tùy ý hoặc dò khóa | Endpoint công khai | Cao | Client credential theo tenant, phạm vi theo `eventKey`. Giới hạn tần suất. Giới hạn kích thước body |
| R-F3 | Payload ghi đè biến hệ thống | Chỉ field được khai báo trong `eventOutParameter` mới thành biến | Thấp | Validate `target` không trùng biến dành riêng (ví dụ `initiator`, `_FLOWABLE_*`) |
| R-F4 | Dữ liệu cá nhân trong nhật ký | `workflow_inbound_event` lưu correlation và payload | TB | Chỉ lưu `payload_digest`. Có chính sách thời gian lưu giữ |

### G. Vận hành và hiệu năng

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-G1 | Instance chờ vô thời hạn | Không có timer thì không có gì kết thúc | TB | Cảnh báo trong designer khi bước chờ không có timer. Màn hình "Đang chờ sự kiện" đọc `ACT_RU_EVENT_SUBSCR` |
| R-G2 | Truy vấn chậm khi nhiều subscription | Không có index `EVENT_TYPE_`. Nhánh `CONFIGURATION_ IS NULL` quét cả các dòng message/signal | Thấp | Theo dõi. Thêm index `(EVENT_TYPE_, TENANT_ID_)` nếu cần (đây là bảng Flowable, phải ghi chú khi nâng cấp) |
| R-G3 | `workflow_inbound_event` phình to | Ghi mỗi sự kiện một dòng | Thấp | Thời gian lưu giữ kèm job dọn |
| R-G4 | Bảng `FLW_EVENT_*` chưa có trên DB | `database-schema-update: false` | TB | Kiểm tra trên môi trường thật. Engine khởi động được thì nhiều khả năng bảng đã có |

### H. Mô hình và designer

| Mã | Rủi ro | Cơ chế | Mức | Giảm thiểu |
|---|---|---|---|---|
| R-H1 | Receive Task sau Event-based Gateway không chạy | Flowable không hỗ trợ | TB | Validate khi phát hành. Gợi ý dùng Intermediate Event Registry Catch |
| R-H2 | Một bước vừa có `workflow:agentTask` vừa có `eventType` | `ReceiveTaskParseHandler` ưu tiên `eventType`, nên Agent listener vẫn chạy nhưng engine chờ sự kiện | TB | Designer chỉ cho một trong hai. Backend từ chối khi phát hành |

## 5. Đề xuất hướng làm

> Phạm vi và cách chia đợt đã được thay bằng mục 0 và mục 6 (Đ1–Đ4). Phần dưới đây giữ lại để tham khảo.

Nguyên tắc: **dùng behavior gốc của Flowable 8.0.0**, XML đúng tên trong `BpmnXMLConstants`, không viết behavior thay thế.

- **P1 · Receive Task thủ công**:
  - Đưa bước chờ vào palette, kèm timer boundary và multi-instance.
  - Thêm API trigger có whitelist (R-F1) và nút "Tiếp tục".
  - Bật mặc định `asyncLeave`.
- **P2 · Receive Event Task**:
  - Thêm `workflow_event_definition` và màn hình quản lý.
  - Deploy `.event` độc lập (R-E1). Panel designer có correlation bắt buộc (R-B1).
  - Thêm endpoint `POST /events/{eventKey}` có `eventId` (R-A2), tenant lấy từ xác thực (R-C3, R-C4), xử lý từng subscription (R-D2).
  - Thêm `workflow_inbound_event`, màn hình "Đang chờ sự kiện".
  - Mở rộng sang Intermediate và Boundary Event Registry: engine đã hỗ trợ, phần làm thêm chủ yếu là UI.
- **P3 · Kafka/JMS, Send-and-Receive**: làm khi có nhu cầu tích hợp thật.

Việc cần làm **trước P2**: xác minh R-C1 (RLS trên bảng `act_*`) và R-G4 trên DB thật.

## 6. Câu hỏi mở về phạm vi

| # | Câu hỏi | Vì sao quan trọng | Đề xuất |
|---|---|---|---|
| Q1 | Ai được tạo channel broker (Kafka/RabbitMQ/JMS)? | Mỗi channel inbound đã deploy sẽ **mở một listener** trên broker dùng chung của hệ thống. Nếu tenant admin được tự đặt topic/queue thì có thể nghe trộm topic của tenant khác | Tenant chỉ chọn topic trong namespace `t.{tenantCode}.*`, được kiểm tra khi publish. Kết nối broker do platform cấu hình |
| Q2 | Channel broker theo từng tenant hay dùng chung, có nhận diện tenant từ message? | Dùng chung với tenant detector thì **tenant nằm trong nội dung message**, trái với nguyên tắc R-C4 | Mỗi tenant một channel, tenant gắn cứng khi deploy. Không bật tenant detector cho tenant thường |
| Q3 | Sự kiện từ broker không khớp instance nào thì xử lý sao? | REST còn trả được 404 để bên gửi thử lại. Broker thì không có kênh phản hồi, message bị bỏ (R-A1 nặng hơn) | Ghi `NO_MATCH` vào nhật ký inbound, hiển thị trên màn hình vận hành. Có cần dead-letter topic không? |
| Q4 | Chống trùng với broker lấy `eventId` từ đâu? | Kafka/RabbitMQ giao ít nhất một lần, nên trùng là bình thường | Trường hoặc header được khai báo trong event model (cờ *Header*). Bắt buộc với channel broker |
| Q5 | Outbound có REST không? | Enterprise **không có** REST outbound (chỉ Kafka/RabbitMQ/JMS/SQS/SNS/Camel/Custom). Gọi HTTP ra ngoài đã có node Service call | Không làm REST outbound. Send Event chỉ đi qua broker |
| Q6 | Outbound gửi lặp khi job retry | Job async có thể chạy lại sau khi đã gửi nhưng trước khi commit, nên gửi ít nhất một lần | Mỗi event gửi ra kèm `eventId` ổn định (theo job/execution) để bên nhận tự lọc trùng |
| Q7 | Pipeline hỗ trợ những gì? | Engine có JSON/XML, nhận diện key bằng field/pointer/xpath, cờ Header/Full payload/Meta | Đợt đầu: JSON, key fixed/jsonField/jsonPointer, cờ Header và Full payload. XML và Meta để sau |
| Q8 | Trường lồng nhau (2025.2.03) | Enterprise dùng data dictionary, hệ thống chưa có | Không làm. Dùng *Full payload* cùng JSON variable thay thế |
| Q9 | Hạ tầng broker trên các môi trường | Cần thêm `spring-kafka`, `spring-rabbit`, `spring-jms`/ActiveMQ, cấu hình và Testcontainers | Mỗi loại bật bằng feature flag (backend và `product.ts`). Môi trường không có broker thì ẩn loại channel đó |
| Q10 | Cluster | Channel deploy trên một node, các node khác chỉ đăng ký listener khi change detection chạy | Bật change detection. Ghi rõ độ trễ đăng ký |

**Đề xuất chia đợt trong phạm vi đã chốt** (mỗi đợt phát hành được độc lập):

1. **Đ1 · Nền tảng:**
   - Receive Task thường kèm API trigger có whitelist.
   - Thư viện event model.
   - Channel REST inbound kèm chống trùng, nhật ký, mã phản hồi.
   - Receive Event Task, Intermediate Catch, Boundary.
   - Màn hình "đang chờ sự kiện".
2. **Đ2 · Khởi tạo bằng sự kiện:** Event Registry Start (có tùy chọn instance duy nhất theo correlation).
3. **Đ3 · Broker inbound:** Kafka, rồi RabbitMQ, rồi JMS. Thứ tự theo nhu cầu tích hợp thật, kèm Q1–Q4 và Q9–Q10.
4. **Đ4 · Gửi:** outbound channel cho broker, Send Event Task, Send-and-Receive Event Task (Q5, Q6).

## Nguồn

Flowable Enterprise (Design reference):
- [Receive Task](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/receive-task)
- [Receive Event Task](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/receive-event-task)
- [Send and Receive Event Task](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/send-and-receive-event-task)
- [Intermediate Event Registry Catch](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/intermediate-event-registry-catch)
- [Message Catching Intermediate Event](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/message-catching-intermediate-event)

Flowable Open Source:
- [BPMN 2.0 Constructs](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)
- [Event Registry Introduction](https://flowable.com/open-source/docs/eventregistry/ch06-EventRegistry-Introduction)
- [Event Registry API](https://www.flowable.com/open-source/docs/eventregistry/ch03-API), [Event Registry REST](https://www.flowable.com/open-source/docs/eventregistry/ch07-REST)
- [BPMN REST API](https://www.flowable.com/open-source/docs/bpmn/ch14-REST)
- [Introducing the Flowable Event Registry](https://flowable.com/blog/introducing-the-flowable-event-registry)

Source và DDL đã đọc (8.0.0):
- Engine: `ReceiveTaskParseHandler`, `ReceiveTaskActivityBehavior`, `ReceiveEventTaskActivityBehavior`, `CorrelationUtil`, `EventInstanceBpmnUtil`, `BpmnEventRegistryEventConsumer`, `BpmnXMLConstants`, `RuntimeService`, `ProcessEngineConfigurationImpl`.
- Event Registry: `BaseEventRegistryEventConsumer`, `DefaultCorrelationKeyGenerator`, `DefaultCorrelationValueTransformer`, `EventDefinitionDeployer`, `EventRegistry`.
- DDL: `flowable.postgres.create.{engine,history,common,eventregistry}.sql`.
- Trong dự án: `V31__harden_agent_operations_and_flowable_rls.sql`, `V17__add_reliable_integration_events.sql`, `V38__process_draft_and_release.sql`.
