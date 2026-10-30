# Cấu hình node gọi API

## Mục tiêu

Panel Service Task đạt mức người thiết kế dùng được như Flowable Work. Operation đã phát hành khai báo tham số. Node chỉ gán biểu thức đầu vào và tên biến đầu ra, rồi hiện mã lỗi BPMN để gắn boundary.

URL, auth và secret nằm trên catalog. Publish thay hợp đồng của lần chạy sau. Sơ đồ không phải sửa, và không ghim một version cũ.

## Phạm vi

Có:

- Tham số đầu vào và đầu ra trên nháp operation, đóng vào snapshot lúc Publish.
- Panel node sinh dòng map từ snapshot đã phát hành.
- Biểu thức Flowable trên execution, literal, và ép kiểu.
- Lỗi map là lỗi kỹ thuật. Lỗi HTTP vẫn là `BpmnError`.

Ngoài phạm vi:

- OAuth2, retry, OpenAPI, multipart, ghim version, JSON path lồng.
- Service task bất đồng bộ và màn incident.
- End-to-end trình duyệt cho designer.

## Kiến trúc

Ba phần giữ nguyên ranh giới.

| Phần | Việc |
|---|---|
| `service-registry` | Lưu hợp đồng, snapshot, đặt giá trị đã tính vào HTTP, đọc field JSON |
| `workflow-engine` | Đọc BPMN, tính biểu thức, ép kiểu, ghi biến process |
| `workflow-boot` | Nối cổng engine với catalog |
| `workflow-agent-web` | Form nháp và panel node |

`service-registry` không gọi Flowable. Vị trí tham số và `jsonField` chỉ có trong snapshot. Delegate không gửi biến process thô sang registry.

Cổng trong `workflow-engine`:

- `findPublished(serviceKey, operationKey)` trả hợp đồng đã phát hành: đầu vào gồm tên, kiểu, bắt buộc; đầu ra gồm tên và kiểu. Không có snapshot thì kết quả rỗng.
- `invoke(serviceKey, operationKey, resolvedInputs)` nhận `Map` tên tham số sang giá trị đã ép kiểu. Trả `Map` tên tham số đầu ra sang giá trị JSON thô. Field thiếu hoặc `null` không có trong map.

`workflow-boot` dịch `ServiceCallException` thành `ServiceRegistryCallException`. Delegate đổi exception đó thành `BpmnError`.

Lỗi ghép request trong `service-registry` là `InvalidServiceRequestException`: URL còn placeholder, hoặc body không phải object JSON. Adapter dịch nó thành `ServiceParameterException`. Delegate không bắt exception đó.

## Dữ liệu

Migration `V33__add_service_operation_parameters.sql` thêm hai cột trên `service_operation_draft`:

- `input_parameters_json TEXT NOT NULL DEFAULT '[]'`
- `output_parameters_json TEXT NOT NULL DEFAULT '[]'`

Mỗi phần tử đầu vào:

- `name`
- `type`: `STRING`, `INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN`, `JSON`
- `required`
- `location`: `PATH`, `QUERY`, `HEADER`, `BODY`

Mỗi phần tử đầu ra:

- `name`
- `type`, cùng bộ kiểu
- `jsonField`

`name` khớp `^[A-Za-z_][A-Za-z0-9_]{0,127}$`, duy nhất trong danh sách của nó, phân biệt hoa thường. Tên đầu vào và đầu ra được trùng nhau. `PATH` luôn `required`. `jsonField` là một key JSON tầng một, dài 1 đến 256 ký tự, không chứa ký tự điều khiển. Dấu `.` trong `jsonField` vẫn là một key, không phải đường dẫn lồng.

Snapshot mỗi operation mang hai mảng này. Snapshot cũ không có mảng thì đọc thành danh sách rỗng. Sửa nháp không đổi snapshot đã Publish.

Lưu operation thay cả hai danh sách. Khóa để trống thì giữ khóa đã lưu.

Catalog từ chối lưu, draft không đổi, HTTP 400, khi:

- tên trùng trong cùng một danh sách, hoặc sai dạng
- `PATH` mà `required` là false
- kiểu hoặc vị trí không thuộc bộ đã cho
- `jsonField` trống, dài quá 256, hoặc có ký tự điều khiển

`GET /services` và `GET /services/{serviceKey}` trả trên mỗi operation cả bản nháp và bản đã phát hành:

- nháp: `inputParameters`, `outputParameters`, method, URL, body, mã lỗi
- đã phát hành: `publishedMethod`, `publishedUrl`, `publishedBpmnErrorCode`, `publishedInputParameters`, `publishedOutputParameters`

Chưa Publish thì các field đã phát hành trống.

## BPMN

`workflow:serviceCall` giữ `serviceKey`, `operationKey`, `inputMapping`, `outputMapping`. Delegate vẫn là `${serviceRegistryDelegate}`.

```json
[{"name":"customerName","expression":"${customerName}"}]
```

```json
[{"name":"id","variable":"leadId"}]
```

Phần tử đầu vào không có `expression`, hoặc đầu ra không có `variable`, bị bỏ qua. Mapping trỏ tới tên không còn trên snapshot bị bỏ qua.

`variable` khớp cùng dạng với `name`. Ô biến sai dạng làm panel cảnh báo, giữ tên hợp lệ trước đó hoặc tên tham số, và không ghi tên sai vào mapping.

## Luồng chạy

1. Delegate đọc binding trên Service Task.
2. Không có snapshot hoặc không có operation: `BpmnError` `SERVICE_NOT_PUBLISHED`. Chưa tính biểu thức và chưa gọi HTTP.
3. Với từng đầu vào trên hợp đồng, lấy `expression` theo tên. Ô đã trim mà trống nghĩa là không có giá trị.
4. Chuỗi còn lại mà chứa `${` hoặc `#{` được đưa nguyên cho `ExpressionManager.createExpression`, rồi `getValue(execution)`. Engine trả `null` hay ném lỗi thì giữ nguyên kết quả đó. Chuỗi khác là literal. `customerName` là chuỗi `customerName`.
5. Kết quả `null` nghĩa là không có giá trị. Chuỗi rỗng là giá trị có mặt.
6. Tham số tùy chọn không có giá trị thì không đưa vào request. Tham số bắt buộc không có giá trị thì dừng trước HTTP.
7. Ép kiểu giá trị có mặt. Hỏng thì dừng trước HTTP.
8. Registry đặt giá trị theo `location` trong snapshot. Tên không có trên hợp đồng thì bỏ qua.
9. Response `2xx`: Jackson đọc body. `jsonField` lấy đúng property tầng một. Thiếu hoặc JSON `null` thì map đầu ra không có tên đó.
10. Delegate ép kiểu giá trị có mặt theo hợp đồng đầu ra, rồi `setVariable` theo `variable`. Tên đầu ra không có trong mapping thì không ghi biến.

Ép kiểu:

- `STRING`: `String` giữ nguyên. `Boolean` thành `true` hoặc `false`. Số nguyên, kể cả `1.0`, thành chữ số không phần thập phân. `Double` có phần thập phân dùng `Double.toString`. `Map` và `List` thì lỗi.
- `INTEGER`: giá trị nguyên trong miền `int`, hoặc chuỗi đã trim đúng số nguyên trong miền đó. `1.0` được. `1.5` và chuỗi `1.0` thì lỗi.
- `LONG`: cùng quy tắc, miền `long`.
- `DOUBLE`: số hữu hạn, hoặc chuỗi số hữu hạn. `NaN` và vô cực thì lỗi.
- `BOOLEAN`: `Boolean`, hoặc đúng chuỗi `true` hay `false`.
- `JSON`: object, mảng, chuỗi, số, boolean, hoặc chuỗi parse được thành JSON. JSON `null` nghĩa là không có giá trị.

`PATH`, `QUERY` và `HEADER` gửi dạng chuỗi. Kiểu `JSON` ở ba vị trí đó là JSON gọn, không khoảng trắng thừa. `BODY` giữ kiểu: số vẫn là số, object và mảng được chèn thẳng, không bọc thành chuỗi.

`PATH` thay `${name}` trên URL bằng giá trị chuỗi. Sau khi thay, URL còn `${` thì dừng trước HTTP. Query dùng cách encode hiện có.

Có tham số `BODY` thì body bắt đầu từ `bodyTemplate`. Template trống được xem là `{}`. Template không phải object JSON thì `InvalidServiceRequestException` trước HTTP. Mỗi tham số `BODY` có giá trị thì ghi đè field đúng tên tham số. Tham số không có giá trị thì xóa field đó khỏi object. Không có tham số `BODY` thì gửi `bodyTemplate` như đã lưu. Tên `PATH` không xuất hiện trên URL thì bỏ qua giá trị đó.

## Lỗi

`ServiceParameterException` là lỗi kỹ thuật, không phải `BpmnError`. Delegate không bắt nó. Giao dịch process rollback. Message có `serviceKey`, `operationKey` và lý do. Lỗi của một tham số thì thêm tên tham số. Message không chứa giá trị đã tính. Biểu thức ném lỗi thì bọc lại, giữ cause.

Delegate ném `ServiceParameterException` khi thiếu tham số bắt buộc, biểu thức ném lỗi, hoặc không ép được kiểu đầu vào. Ba trường hợp này xảy ra trước `invoke`. Không ép được kiểu đầu ra cũng là `ServiceParameterException`, sau khi `invoke` đã trả về.

`InvalidServiceRequestException` thành `ServiceParameterException` khi URL còn placeholder hoặc body không phải object JSON. HTTP client chưa chạy.

Sai kiểu đầu ra xảy ra sau khi HTTP đã gửi. Rollback process không hoàn tác lệnh gọi đó.

Các mã sau vẫn là `BpmnError`:

- chưa phát hành: `SERVICE_NOT_PUBLISHED`
- status nằm trong danh sách lỗi của operation: mã đã cấu hình, mặc định `HTTP_ERROR`
- status khác ngoài `2xx`: `HTTP_ERROR`
- timeout hoặc mất kết nối: `SERVICE_UNAVAILABLE`

## Giao diện

Catalog sửa nháp. Bấm một thao tác trong danh sách thì form nhận method, URL, auth, timeout, mã lỗi, mẫu body và hai bảng tham số. Ô khóa để trống.

Bảng đầu vào có tên, kiểu, bắt buộc, vị trí. Chọn `PATH` thì bật bắt buộc và khóa ô đó. Đổi sang vị trí khác thì mở khóa, giá trị bắt buộc vẫn là true cho đến khi người dùng tắt. Bảng đầu ra có tên, kiểu, field JSON.

Panel Service Task dùng field đã phát hành:

- chọn dịch vụ và thao tác đã có `publishedUrl`
- dòng chỉ đọc: `publishedMethod`, `publishedUrl`, `publishedBpmnErrorCode`
- mỗi đầu vào một ô biểu thức
- mỗi đầu ra một ô tên biến

Chưa có phần tử mapping cho một tên thì điền sẵn `${tên}` và tên biến bằng tên tham số. Phần tử đã lưu, kể cả biểu thức trống, được giữ. Đổi dịch vụ thì xóa thao tác và cả hai mapping. Đổi thao tác thì giữ phần tử trùng tên; tên mới nhận giá trị sẵn; tên mất thì rời mapping.

Lưu sơ đồ khi còn ô bắt buộc trống: hiện cảnh báo và vẫn ghi BPMN. Lúc chạy sẽ rollback. Ô biến sai dạng thì cảnh báo và giữ một tên hợp lệ, như mục BPMN đã nêu.

Khối «Loại bước» dùng điều kiện task thường và không phải Service Task. `isTask` giữ nguyên, nên bộ lắng nghe không đổi.

Operation phát hành từ trước, snapshot không có tham số, hiện hai bảng trống và gọi HTTP như hiện tại.

## Kiểm tra

Catalog, `ServiceCatalogTest` và `JdbcServiceCatalogTest`:

- từ chối tên trùng, tên sai dạng, và `PATH` không bắt buộc; draft không đổi
- Publish đưa hai bảng vào snapshot; sửa nháp không đổi snapshot đó
- API trả nháp và bản đã phát hành riêng nhau
- snapshot cũ không có mảng thì thành danh sách rỗng

`RequestFactoryTest`:

- giá trị đã tính đi đúng `PATH`, `QUERY`, `HEADER`, `BODY`
- `BODY` giữ số, boolean, object và mảng
- tham số tùy chọn không có giá trị thì vắng trên request
- `${name}` trên URL chỉ thay bằng giá trị `PATH`
- URL còn placeholder, hoặc body template không phải object, thì lỗi trước khi tạo request gửi đi

`CallClassifierTest`:

- Jackson đọc field tầng một
- field thiếu hoặc `null` thì kết quả không có tên đó
- object và mảng giữ nguyên cấu trúc

`ServiceRegistryDelegateTest`:

- `${customerName}` lấy biến trên execution; chuỗi trần là literal
- thiếu bắt buộc, biểu thức hỏng, hoặc sai kiểu thì `ServiceParameterException`, không gọi `invoke`
- đầu ra ép kiểu rồi `setVariable` theo `outputMapping`
- mapping cũ hoặc tên không còn trên hợp đồng thì bỏ qua
- `ServiceRegistryCallException` vẫn thành `BpmnError` với `SERVICE_NOT_PUBLISHED`, mã của operation, `HTTP_ERROR`, `SERVICE_UNAVAILABLE`

Web:

- panel sinh dòng từ hợp đồng đã phát hành, điền giá trị sẵn, và giữ ô đã lưu khi đổi thao tác nếu trùng tên
- chọn `PATH` trên form catalog thì bắt buộc bật và khóa
- Service Task không hiện «Loại bước»; điều kiện này không đổi `isTask`
