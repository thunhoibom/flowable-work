# Quy trình con

## Mục tiêu

Người thiết kế dùng được quy trình con ở mức production:

- Gom nhóm bước trong cùng quy trình (embedded subprocess), gắn hạn xử lý hoặc xử lý lỗi cho cả nhóm, và lặp nhóm bước theo danh sách.
- Bắt sự kiện trong một phạm vi (event subprocess).
- Gọi một quy trình đã phát hành khác (call activity), và ánh xạ dữ liệu vào và ra.
- Người vận hành nhìn thấy cây instance cha–con, đi lại giữa các tầng, và thao tác an toàn trên cả cây.

Mức tham chiếu là trải nghiệm Flowable Design/Work: chọn quy trình được gọi từ danh sách, bảng mapping in/out, đi xuống sơ đồ con, liên kết instance cha–con. Nền tảng là behavior gốc của Flowable 8.0.0, không thay behavior nào.

## Phụ thuộc

Call activity (P2 trở đi) cần làm xong `2026-11-05-draft-publish-design.md` trước. Sau việc đó, version mới nhất trong engine luôn là bản đang chạy, nên `CallActivityBehavior` gốc chọn đúng version mà không cần vá.

Embedded và event subprocess (P1) không phụ thuộc, làm song song được.

## Căn cứ Flowable 8.0.0

Đã đọc trên source jar `8.0.0` trong `~/.m2`:

- `SubProcessActivityBehavior`: bắt buộc có đúng một none start event. Execution của subprocess là scope (`setScope(true)`). `dataObject` thành biến local. Engine đăng ký event subprocess bên trong khi vào scope.
- `CallActivityBehavior`:
  - Theo key, lấy latest trong tenant (`findLatestProcessDefinitionByKeyAndTenantId`). Definition bị suspended thì ném lỗi lúc bắt đầu.
  - Thứ tự gán biến: dataObjects → `inheritVariables` → `<flowable:in>`.
  - `completing()` map `<flowable:out>` về cha. `completed()` ném lỗi khi cha bị suspended.
- `StartProcessInstanceInterceptor`:
  - `beforeStartSubProcessInstance` chạy **trước** khi biến được gán. Hook này thấy được execution của call activity.
  - `afterStartSubProcessInstance` chạy sau khi biến đã gán lên instance con, và trước khi instance con bắt đầu chạy.
- `IOParameter`: `source`, `sourceExpression`, `target`, `targetExpression`, `transient`.
- `ProcessInstanceQuery`/`HistoricProcessInstanceQuery` có `superProcessInstanceId`, `excludeSubprocesses`. `Execution` có `getRootProcessInstanceId()` và `getSuperExecutionId()`.
- `BpmnXMLConverter` đọc `BPMNShape`/`BPMNEdge` theo tên phần tử, không phân biệt plane. XML có nhiều `BPMNDiagram` (bpmn-js tạo ra khi subprocess thu gọn) vẫn parse được.

## Mức tương thích với Flowable 8.0.0

Đúng chuẩn:

- Mọi phần tử và thuộc tính dùng đúng tên mà `BpmnXMLConstants` 8.0.0 định nghĩa. Không có thuộc tính riêng của hệ thống trên subprocess hay call activity.
- Dùng behavior gốc cho cả subprocess lẫn call activity. Phần riêng của hệ thống chỉ cắm qua extension point chính thức: `StartProcessInstanceInterceptor`, `FlowableEventListener`.
- Model mang sang engine Flowable khác vẫn chạy với cùng ý nghĩa.

Lệch có chủ đích:

| Điểm | Flowable 8.0.0 | Hệ thống | Hệ quả |
|---|---|---|---|
| Phạm vi tính năng | Đủ mọi thuộc tính call activity, mọi loại event subprocess, ad-hoc, transaction | Tập con, guard chặn phần còn lại lúc phát hành | XML hợp lệ với Flowable vẫn có thể bị từ chối ở đây |
| Biến của con | `in` ghi được mọi biến | `tenantId`/`tenantCode` luôn lấy từ cha | Chặt hơn Flowable |
| Độ sâu gọi | Không giới hạn | Tối đa `max-call-depth` | Chặt hơn Flowable |
| Đồng bộ | Optimistic locking trong engine | Thêm khoá Redis theo instance gốc | Chỉ bổ sung |

## Hiện trạng dự án và các chỗ hỏng

| Chỗ | Hiện trạng | Hậu quả khi có quy trình con | Xử lý |
|---|---|---|---|
| Version | Lưu = deploy, active nằm ở bảng riêng | Call activity gọi bản nháp | Spec draft-publish |
| Biến tenant | `tenantId`, `tenantCode` chỉ được gắn lúc start qua API | Instance con không có `tenantId`, nên `FlowableAssigneeResolver` hỏng | Interceptor |
| Gán người tham gia | `FlowableUserTaskPathResolver` duyệt trong `getMainProcess()` | Không vào embedded subprocess, coi call activity như bước thường | P1, P2 |
| Kiểm tra đầu vào Agent | `WorkflowInteractionGuard.validateStart` chỉ chạy trên API start | Instance con không bị kiểm tra | Interceptor |
| Khoá | `ProcessInstanceTransactionExecutor` khoá theo id của từng instance | Con kết thúc thì chạy tiếp cha, khác khoá | Khoá theo id gốc |
| Kết thúc instance | Cho kết thúc bất kỳ instance nào | Kết thúc con thì cha chờ mãi | P3 |
| Danh sách instance | Liệt kê mọi instance | Con lẫn với gốc | P3 |
| Designer | `sanitizeAttributes` xoá thuộc tính `flowable:` chưa khai báo | Mất cấu hình call activity khi lưu | P1, P2 |

## Phạm vi

| Giai đoạn | Nội dung |
|---|---|
| P1 | Embedded subprocess (mở rộng/thu gọn, đi xuống sơ đồ con), multi-instance, boundary timer/error. Event subprocess với start error/timer. Path resolver và guard hiểu scope lồng nhau |
| P2 | Call activity: interceptor (biến tin cậy, kiểm tra đầu vào, độ sâu), guard, bảng tham chiếu, chống vòng gọi, khoá theo instance gốc, panel cấu hình |
| P3 | Vận hành: cây instance, breadcrumb, lọc instance gốc, chặn thao tác nguy hiểm, phân tích ảnh hưởng khi phát hành hoặc ngừng phát hành, audit |
| P4 (tuỳ chọn) | Message/signal. Chọn người tham gia xuyên call activity |

Ngoài phạm vi:

- Ad-hoc subprocess. Nhu cầu case-like nên đi theo CMMN.
- Transaction subprocess và compensation.
- Gọi cố định một version cũ. Flowable không hỗ trợ. Muốn khoá hành vi thì tạo key riêng.
- `calledElementType="id"`, `fallbackToDefaultTenant`, `sameDeployment`, `calledElementBinding`, gọi quy trình khác tenant.
- `calledElement` dạng biểu thức `${...}`.
- `inheritVariables="true"`.
- Dynamic injection, di trú instance sang version mới.

## BPMN

### Embedded subprocess

```xml
<subProcess id="review" name="Duyệt hồ sơ">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="${reviewers}" flowable:elementVariable="reviewer"/>
  <startEvent id="review_start"/>
  <userTask id="review_task" flowable:assignee="${reviewer}"/>
  <endEvent id="review_end"/>
  ...
</subProcess>
<boundaryEvent id="review_timeout" attachedToRef="review" cancelActivity="true">
  <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>
</boundaryEvent>
```

Quy tắc:

- Mỗi subprocess thường có đúng một none start event và ít nhất một end event.
- Subprocess lồng tối đa `max-nesting-depth` tầng, mặc định 3.
- Event subprocess (`triggeredByEvent="true"`) không có sequence flow vào hoặc ra, và có đúng một start event loại error hoặc timer. P1 không nhận các loại start khác.
- Boundary event trên subprocess: timer, error. Message và signal để P4.

### Call activity

```xml
<callActivity id="callPayment" name="Thanh toán" calledElement="payment"
    flowable:inheritBusinessKey="true"
    flowable:idVariableName="paymentInstanceId"
    flowable:completeAsync="true">
  <extensionElements>
    <flowable:in source="orderId" target="orderId"/>
    <flowable:in sourceExpression="${order.total}" target="amount"/>
    <flowable:out source="paymentStatus" target="paymentStatus"/>
  </extensionElements>
</callActivity>
```

Call activity luôn chạy bản **đang phát hành** của `payment`. Bản nháp không bao giờ được gọi. Nếu `payment` bị ngừng phát hành, engine ném lỗi suspended ngay tại call activity.

Quy tắc lúc phát hành, mọi vi phạm trả `BAD_REQUEST`:

- `calledElement` là key hợp lệ (`^[A-Za-z_][A-Za-z0-9_.-]{0,127}$`), không chứa `${` hoặc `#{`.
- Key đó đang phát hành trong tenant hiện tại (latest không suspended).
- `calledElementType` vắng hoặc bằng `key`. Không có `fallbackToDefaultTenant`, `inheritVariables`, `sameDeployment`, `calledElementBinding`.
- `<flowable:in>`/`<flowable:out>`: có `target` (hoặc `targetExpression` với `out`), có đúng một trong `source` hoặc `sourceExpression`. Tổng số không quá `max-io-parameters`.
- `target` không phải biến nội bộ theo `ProcessVariablePolicy.isInternalVariable`, và không trùng trong cùng một hướng.
- Call activity multi-instance song song thì bắt buộc `completeAsync="true"`.
- Không tạo vòng gọi A → B → … → A, tính theo bản đang phát hành của từng mắt xích.
- Độ sâu tĩnh của chuỗi gọi không quá `max-call-depth`, mặc định 5.
- Quy trình được gọi phải tự gán được mọi user task đầu tiên của nó, bằng luật gán, assignee cố định hoặc biểu thức từ biến `in`. Nếu không, phát hành bị từ chối, vì P2 chưa có màn chọn người xuyên tầng.

## Engine

### `WorkflowSubProcessStartInterceptor`

Cài đặt `StartProcessInstanceInterceptor`, đăng ký trong `FlowableEngineConfig`. Nếu engine đã có interceptor khác thì bọc lại, không thay thế. Không có behavior riêng nào.

`beforeStartSubProcessInstance`:

- Đếm chuỗi `superExecution` từ execution của call activity lên tới gốc. Vượt `max-call-depth` thì ném `CallActivityException`, kế thừa `FlowableException`. Đây là lưới an toàn cuối cùng, phòng khi kiểm tra tĩnh bị lọt, ví dụ hai quy trình phát hành gần như cùng lúc.

`afterStartSubProcessInstance`:

- Ghi đè `tenantId` và `tenantCode` của instance con bằng giá trị của cha. Quy tắc phát hành đã chặn `in` ghi vào hai biến này, đây là lớp bảo vệ thứ hai.
- Gọi `WorkflowInteractionGuard.validateStart(childKey, childDefinitionId, variables)`. Lỗi thành `CallActivityException` có tên trường bị thiếu, không có giá trị biến, và rollback toàn bộ lệnh.

Không dùng `before` cho biến, vì biến `in` được gán sau hook này. Không override `initializeVariables`, vì Flowable bỏ qua hàm này khi map biến rỗng.

### Lỗi và huỷ

- `BpmnError` mà instance con không bắt sẽ lan lên call activity. Error boundary trên call activity bắt được lỗi này.
- Xoá instance cha xoá cả instance con.
- Lỗi kỹ thuật ở con rollback cả giao dịch, kể cả phần của cha nếu chạy đồng bộ.

Hai điều đầu là hành vi Flowable. Có test kiểm chứng trên 8.0.0.

### Khoá theo instance gốc

`ProcessInstanceTransactionExecutor.execute(processInstanceId, ...)` khoá theo `rootProcessInstanceId`. Người gọi vẫn truyền id của instance mình thao tác. Instance đã kết thúc thì dùng chính id đó. Chỉ có một cấp khoá, nên không deadlock.

### Gán người tham gia

`FlowableUserTaskPathResolver`:

- Gặp `SubProcess` thường: đi vào start event của nó. Gặp end event bên trong: đi tiếp theo outgoing của subprocess.
- Gặp event subprocess: bỏ qua.
- Gặp `CallActivity`: dừng. Đây là ranh giới, giống Agent wait state.
- Tìm phần tử theo `FlowElementsContainer`, không chỉ trong `getMainProcess()`.

## Dữ liệu

Migration `V39__workflow_call_reference.sql`. V38 dành cho draft-publish.

```sql
CREATE TABLE workflow_call_reference (
  tenant_id              VARCHAR(64)  NOT NULL,
  parent_definition_id   VARCHAR(128) NOT NULL,
  parent_definition_key  VARCHAR(128) NOT NULL,
  parent_version         INT          NOT NULL,
  activity_id            VARCHAR(255) NOT NULL,
  called_key             VARCHAR(128) NOT NULL,
  PRIMARY KEY (tenant_id, parent_definition_id, activity_id)
);
CREATE INDEX ix_call_reference_called ON workflow_call_reference (tenant_id, called_key);
```

- Ghi trong cùng giao dịch phát hành. Xoá theo `parent_definition_id` khi xoá version.
- Bật RLS theo `tenant_id`, giống V16.
- Phục vụ chống vòng gọi, «Được dùng ở đâu» và phân tích ảnh hưởng. Chỉ các dòng thuộc bản đang phát hành của cha mới tính là caller.

## API

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/process-definitions/{key}/callers` | Các quy trình đang phát hành có gọi key này |
| `GET` | `/process-definitions/{key}/callees?version=` | Các key mà version này gọi |
| `GET` | `/process-instances/{id}/children` | Instance con trực tiếp: activityId, trạng thái, definition, thời gian |
| `GET` | `/process-instances/{id}/hierarchy` | Chuỗi từ gốc tới instance này, cho breadcrumb |
| `GET` | `/process-instances?rootOnly=true` | Mặc định `true` ở UI. Dùng `excludeSubprocesses` |

- `ProcessInstanceDetailView` thêm `superProcessInstanceId`, `rootProcessInstanceId`, `callActivityId`.
- `TaskView` thêm `rootProcessInstanceId`, `rootBusinessKey`.
- Quyền giữ như role hiện tại. Mọi truy vấn lọc theo tenant.

## Vận hành

- Kết thúc instance con trực tiếp bị từ chối (`CONFLICT`, «Hãy kết thúc instance gốc»). Kết thúc instance gốc thì xoá cả cây. Audit ghi một dòng cho mỗi instance.
- Xoá quy trình bị chặn khi có caller đang phát hành.
- Phát hành một version mới của quy trình được gọi: kết quả kèm cảnh báo khi version mới cần đầu vào bắt buộc mà caller không truyền qua `in`, hoặc khi caller đọc qua `out` một biến mà version mới không còn ghi. Cảnh báo không chặn.
- Ngừng phát hành một quy trình đang có caller: hỏi xác nhận, liệt kê caller. Sau đó các caller sẽ lỗi khi chạy tới call activity.
- Audit thêm `SUBPROCESS_STARTED`, `SUBPROCESS_COMPLETED`, `SUBPROCESS_FAILED` qua `FlowableEventListener`, kèm parent id và root id.
- Log có `rootProcessInstanceId` trong MDC.

## Cấu hình

`SubProcessProperties`, prefix `workflow.subprocess`:

| Khoá | Mặc định | Biến môi trường |
|---|---|---|
| `call-activity-enabled` | `false` | `WORKFLOW_SUBPROCESS_CALL_ACTIVITY_ENABLED` |
| `max-nesting-depth` | `3` | `WORKFLOW_SUBPROCESS_MAX_NESTING_DEPTH` |
| `max-call-depth` | `5` | `WORKFLOW_SUBPROCESS_MAX_CALL_DEPTH` |
| `max-io-parameters` | `64` | `WORKFLOW_SUBPROCESS_MAX_IO_PARAMETERS` |

`call-activity-enabled` chỉ quyết định có cho phát hành call activity hay không. Interceptor luôn chạy.

## Giao diện

### Designer

- Palette:
  - «Thêm quy trình con» tạo `bpmn:SubProcess` mở rộng.
  - «Gọi quy trình khác» tạo `bpmn:CallActivity`, chỉ hiện khi `VITE_CALL_ACTIVITY_ENABLED=true`.
- Thu gọn/mở rộng bằng replace menu. Đi xuống sơ đồ con bằng drilldown có sẵn của bpmn-js 18, có breadcrumb.
- Nhãn: `bpmn:CallActivity` là «Gọi quy trình». Subprocess có `triggeredByEvent` là «Quy trình con sự kiện».
- `flowableModdle` khai báo:
  - `CallActivity`: `calledElementType`, `inheritBusinessKey`, `businessKey`, `idVariableName`, `completeAsync`, `processInstanceName`, `async`
  - kiểu `Out`: `source`, `sourceExpression`, `target`, `targetExpression`
  - `transient` trên `In`/`Out`
- `sanitizeModel` và `ensureAllUserTaskAssignments` chạy trên mọi plane.
- Panel `CallActivityConfigPanel`:
  - Ô chọn quy trình, chỉ liệt kê quy trình đang phát hành trong tenant, hiện version đang chạy.
  - Bảng «Dữ liệu truyền vào», có nút «Gợi ý từ quy trình con» điền sẵn các trường bắt buộc từ start interaction của quy trình được chọn.
  - Bảng «Dữ liệu nhận về».
  - Business key: không có / kế thừa / biểu thức.
  - «Lưu id instance con vào biến».
  - «Hoàn tất bất đồng bộ», tự bật và khoá khi multi-instance song song.
  - Nút «Mở quy trình được gọi».
- Panel `SubProcessConfigPanel`: multi-instance. Với event subprocess: loại start và cờ interrupting.
- Lưu nháp luôn được, kể cả khi còn cảnh báo. Chỉ phát hành mới bị guard chặn.

### Màn instance

- Danh sách mặc định chỉ hiện instance gốc, có công tắc «Hiện cả quy trình con» và cột «Quy trình cha».
- Chi tiết: breadcrumb Gốc › … › Hiện tại. Tab «Quy trình con». Bấm call activity trên sơ đồ để mở instance con, hoặc mở danh sách nếu là multi-instance.
- `ProcessDiagram` tô sáng activity trong subprocess, kể cả subprocess thu gọn.
- Hộp việc: task của con hiện business key và tên quy trình gốc.

### Màn definition

- Tab «Được dùng ở đâu» và «Gọi tới».
- Xác nhận phát hành, ngừng phát hành và xoá có hiện kết quả phân tích ảnh hưởng.

## Kiểm tra

Backend, engine H2 như `WorkflowScriptTaskEngineTest`:

- `SubProcessEngineTest`:
  - biến local của subprocess
  - multi-instance song song có completionCondition
  - boundary timer huỷ cả subprocess
  - event subprocess error bắt `BpmnError` từ delegate bên trong
  - XML nhiều plane từ bpmn-js deploy được
- `CallActivityEngineFactsTest`, hành vi gốc:
  - chạy latest
  - con bị suspended thì lỗi
  - `BpmnError` từ con được error boundary trên call activity bắt
  - xoá cha xoá con
  - `out` local khi `useLocalScopeForOutParameters`
  - 10 con song song với `completeAsync` không gặp optimistic lock
- `WorkflowSubProcessStartInterceptorTest`:
  - con nhận `tenantId` của cha, kể cả khi `in` cố ghi đè (deploy thẳng, bỏ qua guard)
  - thiếu đầu vào bắt buộc thì lỗi, có tên trường
  - vượt `max-call-depth` thì lỗi
  - bản nháp của con không bao giờ được gọi (kiểm tra cùng draft-publish)
- `CallActivityDeploymentGuardTest`: mỗi quy tắc phát hành một assert, có cả vòng gọi trực tiếp và gián tiếp.
- `FlowableUserTaskPathResolverTest`: đi vào subprocess, dừng ở call activity, bỏ qua event subprocess.
- `ProcessInstanceTransactionExecutorTest`: thao tác trên con khoá theo id gốc.
- `WorkflowAdministrationServiceImplTest`: chặn kết thúc con, chặn xoá quy trình đang được gọi.

Web:

- `callActivityPanel.spec.ts`: hàng in/out đổi qua lại với `flowable:In`/`flowable:Out`, tự bật `completeAsync`.
- `bpmn.spec.ts`: XML sau `sanitizeAttributes` giữ đủ thuộc tính call activity và subprocess lồng nhau.
- `processInstanceHierarchy.spec.ts`: breadcrumb và lọc instance gốc.

## Câu hỏi còn mở

- Chọn người tham gia cho user task đầu tiên của con khi không có luật gán (P4). Hướng đi: requirement có khoá `callActivityId/childTaskId`, gán vào con qua biến tin cậy.
- Có cho kết thúc riêng một nhánh con, rồi đi theo error boundary ở cha, hay không. Spec hiện chặn.
- Ngừng phát hành một quy trình đang có caller: chỉ cảnh báo như hiện tại, hay chặn hẳn.
- Message/signal cần API correlation dùng chung, làm ở spec riêng.
