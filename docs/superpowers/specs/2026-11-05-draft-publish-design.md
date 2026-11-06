# Tách bản nháp khỏi engine

## Mục tiêu

Đưa vòng đời quy trình về đúng cách Flowable hiểu: **deploy là phát hành**.

- **Lưu nháp** chỉ ghi vào bảng của hệ thống. Engine Flowable không biết tới bản nháp.
- **Phát hành** mới deploy lên Flowable. Version mới nhất trong engine luôn là bản đang chạy.
- **Ngừng phát hành** thì suspend definition. Không ai start mới được, kể cả qua call activity. Instance đang chạy vẫn chạy tiếp.

Đây là việc nền cho call activity (`2026-11-05-subprocess-design.md`). Làm xong thì call activity dùng được hành vi gốc của Flowable mà không cần vá chọn version.

## Vì sao phải đổi

Cách hiện tại: Lưu = deploy, Phát hành = ghi `workflow_active_version`. Cách này lệch Flowable ở những chỗ sau:

| Chỗ | Flowable 8.0.0 làm gì | Hậu quả với cách hiện tại |
|---|---|---|
| Call activity theo key | Lấy version mới nhất | Chạy bản nháp |
| Timer/message/signal start event | `BpmnDeployer.updateTimersAndEvents` chuyển sang version vừa deploy | Bấm Lưu bản nháp là bản nháp tự chạy theo lịch hoặc theo message |
| `latestVersion()` trong mọi query | Coi là bản chính thức | Phải tra thêm bảng active ở mọi nơi |
| Hộp thoại start | — | `StartProcessDialog` lấy latest rồi lọc `active`. Lưu nháp sau khi phát hành làm quy trình **biến khỏi** hộp thoại. Đây là lỗi đang có |
| Guard lúc deploy | — | Bản nháp sửa dở cũng phải hợp lệ mới lưu được |

## Mô hình mới

```
Bản nháp (bảng workflow_process_draft, mỗi key một bản)
   │  Lưu nháp: chỉ kiểm tra XML đọc được, đúng key, không quá cỡ
   ▼
Phát hành: chạy mọi guard → createDeployment → vN (latest, active)
   ▼
Engine Flowable: v1 … vN. Bản đang chạy = latest và không suspended
```

| Trạng thái của key | Điều kiện | Start mới | Call activity |
|---|---|---|---|
| Chưa phát hành | Chỉ có bản nháp | Không | Không (definition không tồn tại) |
| Đang phát hành | Latest không suspended | Có, chạy latest | Có, chạy latest |
| Ngừng phát hành | Latest suspended | Không | Lỗi `suspended` từ engine |

Bản nháp có thể khác bản đang phát hành. Designer hiển thị «Có thay đổi chưa phát hành» khi hash XML của bản nháp khác hash của latest.

## Thao tác

| Thao tác | Việc xảy ra |
|---|---|
| Tạo quy trình | Tạo bản nháp. Key phải chưa có trong bản nháp lẫn engine |
| Lưu nháp | Cập nhật bản nháp, có kiểm tra `revision` để hai người sửa cùng lúc không ghi đè nhau |
| Phát hành | Guard (script, subprocess, call activity, Agent) → deploy → chép luật gán người từ version phát hành trước → ghi lịch sử phát hành. Tất cả trong một giao dịch |
| Ngừng phát hành | `suspendProcessDefinitionById(latestId, false, null)`. Instance đang chạy không bị suspend |
| Phát hành lại | Activate lại latest |
| Quay về vK | Deploy lại XML của vK thành version mới vN+1, chép luật gán người của vK. Không có thao tác «kích hoạt version cũ», vì Flowable không có khái niệm này |
| Mở vK vào bản nháp | Chép XML của vK đè lên bản nháp, có xác nhận |
| Huỷ thay đổi nháp | Chép XML của latest đè lên bản nháp |
| Xoá version | Chỉ cho xoá version **không phải latest** và không có instance đang chạy. Muốn bỏ bản đang chạy thì dùng Ngừng phát hành |
| Xoá quy trình | Xoá bản nháp, mọi version, luật gán người. Bị chặn khi có instance đang chạy hoặc có quy trình khác đang gọi tới |

## Dữ liệu

```sql
CREATE TABLE workflow_process_draft (
  tenant_id              VARCHAR(64)  NOT NULL,
  process_definition_key VARCHAR(128) NOT NULL,
  name                   VARCHAR(255) NOT NULL,
  bpmn_xml               TEXT         NOT NULL,
  xml_sha256             CHAR(64)     NOT NULL,
  revision               BIGINT       NOT NULL,
  updated_by             VARCHAR(255) NOT NULL,
  updated_at             TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (tenant_id, process_definition_key)
);

CREATE TABLE workflow_process_release (
  id                     BIGSERIAL    PRIMARY KEY,
  tenant_id              VARCHAR(64)  NOT NULL,
  process_definition_key VARCHAR(128) NOT NULL,
  process_definition_id  VARCHAR(128) NOT NULL,
  version                INT          NOT NULL,
  action                 VARCHAR(16)  NOT NULL,  -- PUBLISH, ROLLBACK, SUSPEND, RESUME, MIGRATED
  source_version         INT          NULL,      -- với ROLLBACK
  xml_sha256             CHAR(64)     NOT NULL,
  performed_by           VARCHAR(255) NOT NULL,
  performed_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_process_release_key ON workflow_process_release (tenant_id, process_definition_key, performed_at DESC);
```

- Cả hai bảng bật RLS theo `tenant_id`, giống V16.
- `xml_sha256` tính trên XML đã chuẩn hoá xuống dòng.
- Kích thước XML tối đa `workflow.definition.max-bpmn-bytes`, mặc định 2 MB, kiểm tra cả lúc lưu nháp và lúc phát hành.
- `workflow_active_version` không còn được đọc sau khi chuyển đổi. Bảng được xoá ở một migration sau, khi bản mới đã chạy ổn định qua một kỳ phát hành.

## Luật gán người

Luật vẫn gắn theo `process_definition_id` như hiện nay. Lý do: đổi sang luật theo bản nháp chạm vào toàn bộ module assignment, và có thể làm riêng sau.

- Khi phát hành, hệ thống tự chạy logic `AssignmentRuleService.inherit` sẵn có: chép luật từ version phát hành trước sang version mới, bỏ qua node không còn tồn tại.
- Kết quả phát hành trả về danh sách user task **mới** chưa có luật. Designer hiện cảnh báo và có nút mở màn luật gán người cho version vừa phát hành.
- Luật của version đang chạy vẫn sửa trực tiếp được như hiện nay.

## Start

- Start theo key: lấy latest trong tenant. Definition bị suspended thì trả `CONFLICT` «Quy trình đã ngừng phát hành».
- Start theo `processDefinitionId`: chỉ nhận khi id đó là latest và không suspended. Id của version cũ trả `BAD_REQUEST`. Đây là thay đổi hành vi với client `SERVICE`, cần báo trước.
- `StartProcessDialog` lấy danh sách latest không suspended từ API. Không tự lọc ở client nữa.

## API

| Method | Path | Thay cho |
|---|---|---|
| `GET` | `/workflow/management/process/drafts/{key}` | — |
| `PUT` | `/workflow/management/process/drafts/{key}` (body có `revision`) | `POST /deploy` khi bấm Lưu |
| `POST` | `/workflow/management/process/drafts` (tạo mới) | — |
| `POST` | `/workflow/management/process/{key}/publish` (body có `revision` của bản nháp) | `POST /deploy` + `POST /definition/activate` |
| `POST` | `/workflow/management/process/{key}/suspend` · `/resume` | — |
| `POST` | `/workflow/management/process/{key}/rollback` (body `version`) | `POST /definition/activate` với version cũ |
| `POST` | `/workflow/management/process/drafts/{key}/load-version` (body `version`) | — |
| `GET` | `/workflow/management/process/{key}/releases` | — |

- `POST /deploy` được giữ cho role `SERVICE` (CI/CD đẩy quy trình từ git). Nó đi qua đúng đường phát hành và cập nhật luôn bản nháp.
- `POST /definition/activate` và `GET /definition/active` trả `410 Gone` sau một kỳ phát hành. Trong kỳ chuyển tiếp, `activate` với version bằng latest thì resume, version cũ thì rollback. `active` trả latest nếu không suspended.
- `ProcessDefinitionSummaryView.publishStatus`: `draft_only`, `published`, `suspended`, kèm cờ `hasUnpublishedChanges`.

## Chuyển đổi dữ liệu cũ

Chạy một lần cho mỗi tenant, bằng Java (cần API Flowable), qua lệnh quản trị `POST /workflow/admin/migrations/draft-publish`, role `WF_PLATFORM_ADMIN`. Lệnh idempotent và ghi kết quả từng key.

| Tình huống của key | Việc làm |
|---|---|
| Active = latest | Tạo bản nháp từ XML latest. Ghi release `MIGRATED` |
| Active < latest (có bản nháp nằm trong engine) | Tạo bản nháp từ XML **latest**, để giữ phần đang sửa. Deploy lại XML của active thành version mới, chép luật từ active sang. Version mới thành latest. Ghi `MIGRATED` |
| Không có active | Tạo bản nháp từ XML latest. Suspend mọi definition của key, không suspend instance. Ghi `MIGRATED` |

- Instance đang chạy không bị ảnh hưởng, vì instance gắn với definition id cố định.
- Trước khi chạy: báo cáo dry-run (`?dryRun=true`) liệt kê từng key và việc sẽ làm.
- Version cũ không bị xoá. Lịch sử giữ nguyên.

## Giao diện

- Designer: nút **Lưu nháp**, **Phát hành**, menu «Khác» gồm Ngừng phát hành / Phát hành lại / Huỷ thay đổi nháp. Nhãn trạng thái: Bản nháp · Đang chạy vN · Đã ngừng · Có thay đổi chưa phát hành.
- Chọn version cũ: chỉ xem, có nút «Mở vào bản nháp» và «Quay về version này».
- Lưu nháp khi XML không hợp lệ với guard: vẫn lưu, hiện cảnh báo. Phát hành mới bị chặn.
- Lưu khi `revision` cũ: báo «Bản nháp đã được người khác sửa», cho tải lại hoặc ghi đè.
- Tab «Lịch sử phát hành» lấy từ `workflow_process_release`.
- Màn danh sách quy trình: cột trạng thái theo `publishStatus`.

## Kiểm tra

- `ProcessDraftServiceTest`: tạo, lưu, xung đột `revision`, giới hạn kích thước, key trùng với engine.
- `ProcessPublishServiceTest` (engine H2):
  - phát hành chạy guard trước `createDeployment`
  - lỗi guard thì không có gì được deploy và bản nháp không đổi
  - luật được chép từ version trước
  - ngừng phát hành chặn start và chặn call activity
  - quay về vK tạo vN+1 với XML của vK
- `TimerStartDraftTest`: lưu nháp có timer start event không tạo job. Phát hành mới tạo job.
- `DraftPublishMigrationTest`: ba tình huống chuyển đổi, chạy hai lần cho cùng kết quả.
- `WorkflowRuntimeServiceImplTest`: start theo id cũ bị từ chối, start khi suspended trả `CONFLICT`.
- Web: designer đổi nút và trạng thái, `StartProcessDialog` không còn tự lọc `active`.

## Câu hỏi còn mở

- Luật gán người theo bản nháp, để cấu hình trước khi phát hành. Spec này để sau.
- Lịch sử từng lần lưu nháp, kiểu xem lại các revision. Hiện chỉ giữ bản mới nhất.
- Thời điểm xoá hẳn `workflow_active_version` và tắt hai API cũ.
- Thông báo cho các hệ thống `SERVICE` đang start bằng `processDefinitionId` của version cũ.
