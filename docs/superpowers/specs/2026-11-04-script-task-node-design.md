# Node chạy script

## Mục tiêu

Người thiết kế quy trình thêm được một bước tự động chạy đoạn JavaScript ngắn để tính toán hoặc biến đổi dữ liệu. Bước này dùng `scriptTask` chuẩn của Flowable 8.0.0. Script chạy trong sandbox: không gọi được class Java, không thấy `execution`, chỉ đọc được biến đã khai báo, có giới hạn thời gian, bộ nhớ và độ sâu stack.

Logic phức tạp vẫn dùng node gọi dịch vụ.

## Căn cứ Flowable 8.0.0

Kiểm tra trên source tag `flowable-8.0.0`:

- `ScriptTaskXMLConverter` đọc `scriptFormat`, `<script>`, `flowable:resultVariable`, `flowable:skipExpression`, `flowable:autoStoreVariables`, `flowable:doNotIncludeVariables` và `<flowable:in>`.
- `ScriptTaskActivityBehavior` mới ở 8.0.0: khi có `inParameters`, script chỉ thấy các biến đã truyền vào.
- Module `flowable-secure-javascript` (Rhino `1.7.14.1`) có `SecureJavascriptConfigurator`, `SecureScriptContextFactory` và `SecureScriptClassShutter`.
- `SecureJavascriptTaskActivityBehavior` **bỏ qua** `inParameters`. Nó đưa toàn bộ biến process và đối tượng `execution` vào script. Vì vậy hệ thống không dùng behavior này. Hệ thống chỉ dùng context factory và class shutter của module.
- `flowable-spring-boot-starter-process` không kèm engine script nào. Java 25 không có Nashorn. Không thêm Groovy hay Nashorn.

## Vì sao Rhino, không phải `nashorn-core`

Flowable 8.x, cả bản mã nguồn mở lẫn Enterprise 2025.2 và 2026.1, chạy JavaScript qua JSR-223. Tài liệu Flowable hướng dẫn thêm `org.openjdk.nashorn:nashorn-core` khi chạy Java 17 trở lên. Đường này không có sandbox.

Hệ thống chọn Rhino qua `flowable-secure-javascript` vì hai lý do:

- `WF_ADMIN` của từng tenant được deploy quy trình, và mọi tenant chạy chung một JVM. Nashorn qua JSR-223 cho script gọi mọi class Java.
- Nashorn không dừng được một script chạy mãi. `Thread.stop` không còn dùng được trên Java 25. Rhino dừng script ngay trên luồng đang chạy, nhờ đếm số lệnh.

Cái giá phải trả:

- Script viết theo kiểu Flowable Enterprise, ví dụ dùng `execution.setVariable`, không chạy được ở đây.
- Rhino chỉ hỗ trợ một phần ES6.

## Phạm vi

Có:

- `scriptTask` với `scriptFormat="javascript"`, đầu vào qua `<flowable:in>`, kết quả qua `flowable:resultVariable`.
- Behavior riêng chạy Rhino với sandbox. Behavior này áp dụng cho mọi `scriptTask`, kể cả khi tính năng đang tắt.
- Kiểm tra lúc deploy: chặn script sai dạng, script listener, Groovy và các ngôn ngữ khác.
- Cờ `workflow.script-task.enabled`, mặc định `false`.
- Palette, panel cấu hình và nhãn trên designer.

Ngoài phạm vi:

- Groovy, Nashorn, GraalJS, JUEL làm `scriptFormat`.
- Script listener (`type="script"`), script trên điều kiện đường nối, script trong HTTP task.
- Ném `BpmnError` từ script. Lỗi script là lỗi kỹ thuật.
- Gọi bean, gọi HTTP hay đọc file từ script.
- Listener `class=` tùy ý hiện có. Đây là bề mặt riêng, xử lý ở việc khác.
- End-to-end trình duyệt cho designer.

## BPMN

```xml
<scriptTask id="calcTotal" name="Tính tổng" scriptFormat="javascript"
            flowable:resultVariable="total">
  <extensionElements>
    <flowable:in source="amount" target="amount"/>
    <flowable:in sourceExpression="${order.qty}" target="qty"/>
  </extensionElements>
  <script><![CDATA[amount * qty]]></script>
</scriptTask>
```

- `scriptFormat` đúng chuỗi `javascript`, chữ thường.
- Giá trị của biểu thức cuối cùng trong script là kết quả. Không dùng `return` ở tầng ngoài.
- `flowable:resultVariable` tùy chọn. Không có thì kết quả bị bỏ.
- Mỗi `<flowable:in>` có `target` và đúng một trong `source` hoặc `sourceExpression`.
- `flowable:autoStoreVariables` vắng hoặc `false`.
- `flowable:doNotIncludeVariables` không có tác dụng. Script luôn chỉ thấy đầu vào đã khai báo.
- `flowable:skipExpression`, `flowable:async`, multi-instance và execution listener dạng class/expression/delegateExpression vẫn dùng như task thường.

## Kiểm tra lúc deploy

`WorkflowDefinitionServiceImpl.deploy` gọi `ScriptTaskDeploymentGuard.check(bpmnXml)` trước khi kiểm tra Agent. Lỗi trả `BusinessException` `BAD_REQUEST`. Không có gì được deploy.

Hệ thống từ chối khi:

- có `scriptTask` trong lúc `workflow.script-task.enabled=false`
- `scriptFormat` khác `javascript`
- script trống sau khi trim, hoặc dài hơn `max-script-length`
- `autoStoreVariables` khác `false`
- `resultVariable` (hoặc `resultVariableName`) không khớp `^[A-Za-z][A-Za-z0-9_]{0,127}$`, hoặc là biến nội bộ theo `ProcessVariablePolicy.isInternalVariable`
- số `<flowable:in>` lớn hơn `max-inputs`
- `target` không khớp `^[A-Za-z_$][A-Za-z0-9_$]{0,127}$`, hoặc trùng trong cùng một task
- `<flowable:in>` không có, hoặc có cả hai, `source` và `sourceExpression`
- `source` không khớp mẫu tên biến ở trên
- `sourceExpression` không chứa `${` hoặc `#{`
- có `<flowable:out>` trong `scriptTask`

Trên toàn bộ tài liệu, hệ thống từ chối khi:

- có phần tử `script` nằm ngoài một `scriptTask`, ví dụ `<flowable:script>` trong listener
- có listener với `type="script"`
- có listener với `class` khớp `^org\.flowable\.scripting\.` hoặc `^org\.flowable\..*Script[A-Za-z]*Listener$`
- có thuộc tính `language` trên bất kỳ phần tử nào
- có thuộc tính `scriptFormat` ngoài `scriptTask`

XML được đọc với `FEATURE_SECURE_PROCESSING` và cấm DOCTYPE, giống `XmlServiceTaskBindingParser`.

## Engine

`FlowableEngineConfig` thêm `WorkflowScriptConfigurator` vào engine. Lớp này kế thừa `SecureJavascriptConfigurator`:

- `enableClassWhiteListing=true`, danh sách whitelist rỗng: script không thấy class Java nào.
- `enableAccessToBeans=false`.
- `maxScriptExecutionTime`, `maxMemoryUsed`, `maxStackDepth`, `nrOfInstructionsBeforeStateCheckCallback` lấy từ cấu hình.
- `scriptOptimizationLevel=-1`, chế độ thông dịch, cần cho việc đếm lệnh.
- `beforeInit` khởi tạo context factory toàn cục, rồi đăng ký `WorkflowScriptTaskParseHandler` thay cho `SecureJavascriptTaskParseHandler`.

`ContextFactory.initGlobal` chỉ chạy một lần trong một JVM. Giới hạn của lần khởi tạo đầu tiên được giữ cho đến khi tắt JVM.

`WorkflowScriptTaskParseHandler` gắn `WorkflowScriptTaskActivityBehavior` cho mọi `scriptTask`, kể cả khi ngôn ngữ không phải `javascript`. Behavior kế thừa `ScriptTaskActivityBehavior`, nên giữ được `skipExpression`, cơ chế đi tiếp và cách Flowable truyền `BpmnError`. Behavior chỉ thay `executeScript`.

## Luồng chạy

1. Ngôn ngữ khác `javascript`: `ScriptTaskException`. Script không chạy.
2. `IOParameterUtil.processInParameters` tính các `<flowable:in>` trên execution, ghi vào một `LinkedHashMap` theo thứ tự khai báo.
3. `ContextFactory.getGlobal().enterContext()` lấy context từ factory bảo mật, mức ngôn ngữ `Context.VERSION_ES6`. Scope tạo bằng `initSafeStandardObjects()`, nên không có `Packages`, `java` hay `JavaImporter`. Rhino `1.7.14` chỉ hỗ trợ một phần ES6, nên panel ghi rõ giới hạn này.
4. Mỗi đầu vào được đổi sang giá trị JavaScript thuần rồi gắn vào scope theo `target`. Không có `execution`, `task` hay bean.
5. `evaluateString` chạy script, tên nguồn là id của task.
6. Kết quả đổi sang Java. Có `resultVariable` thì `execution.setVariable`.

Đổi đầu vào sang JavaScript:

- `null`: `null`
- `String`, `Character`: chuỗi
- `Boolean`: boolean
- `Integer`, `Short`, `Byte`: số
- `Long`: số khi trị tuyệt đối không quá `2^53`, ngoài miền đó thì lỗi
- `Float`, `Double`: số hữu hạn; `NaN` và vô cực thì lỗi
- `Map` có khóa chuỗi: object, đệ quy
- `List` hoặc mảng: array, đệ quy
- kiểu khác, kể cả `Date`, `BigDecimal`, `JsonNode`: lỗi, message có tên đầu vào và tên kiểu

Đổi kết quả sang Java:

- `undefined`, `null`: `null`
- chuỗi: `String`
- boolean: `Boolean`
- số nguyên hữu hạn, trị tuyệt đối không quá `2^53`: `Long`; số hữu hạn khác: `Double`; `NaN` và vô cực thì lỗi
- array: `ArrayList`, đệ quy
- object thường: `LinkedHashMap` khóa chuỗi, đệ quy
- function, Date, RegExp, đối tượng Java hay kiểu khác: lỗi
- lồng sâu quá 32 tầng: lỗi

`Map` và `List` được lưu thành biến serializable, giống kiểu `JSON` của node gọi dịch vụ.

## Lỗi

`ScriptTaskException` kế thừa `FlowableException`. Nó không phải `BpmnError`. Giao dịch process rollback. Message có id của task và lý do. Message không chứa giá trị của biến.

Trường hợp:

- script `throw`: lý do là message của lỗi JavaScript, cắt còn 500 ký tự
- lỗi cú pháp hoặc lỗi chạy của Rhino (`RhinoException`): lý do là `details()`, cắt còn 500 ký tự
- vượt thời gian hoặc bộ nhớ: Rhino ném `java.lang.Error` từ `SecureScriptContextFactory`; behavior bắt lỗi này, trừ `VirtualMachineError`
- vượt độ sâu stack: `RhinoException`
- truy cập Java bị class shutter chặn: `RhinoException`
- đầu vào hoặc kết quả không đổi được kiểu

Lỗi khi tính `<flowable:in>` giữ nguyên exception của Flowable.

## Cấu hình

`ScriptTaskProperties`, prefix `workflow.script-task`:

| Khóa | Mặc định | Biến môi trường |
|---|---|---|
| `enabled` | `false` | `WORKFLOW_SCRIPT_TASK_ENABLED` |
| `max-script-length` | `20000` | `WORKFLOW_SCRIPT_TASK_MAX_SCRIPT_LENGTH` |
| `max-inputs` | `32` | `WORKFLOW_SCRIPT_TASK_MAX_INPUTS` |
| `max-execution-millis` | `2000` | `WORKFLOW_SCRIPT_TASK_MAX_EXECUTION_MILLIS` |
| `max-memory-bytes` | `8388608` | `WORKFLOW_SCRIPT_TASK_MAX_MEMORY_BYTES` |
| `max-stack-depth` | `64` | `WORKFLOW_SCRIPT_TASK_MAX_STACK_DEPTH` |
| `instruction-check-interval` | `100` | `WORKFLOW_SCRIPT_TASK_INSTRUCTION_CHECK_INTERVAL` |

`enabled` chỉ quyết định deploy có nhận `scriptTask` hay không. Bản đã deploy trước đó vẫn chạy qua behavior sandbox.

Giới hạn bộ nhớ cần JVM hỗ trợ `com.sun.management.ThreadMXBean#getThreadAllocatedBytes`. HotSpot của Java 25 có hàm này.

## Quyền

Endpoint deploy giữ nguyên `hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')`, role lấy từ EIM. Không thêm role mới. Sandbox và kiểm tra lúc deploy là lớp bảo vệ.

## Giao diện

- `VITE_SCRIPT_TASKS_ENABLED=true` mới hiện mục palette «Thêm bước script». Mục này tạo `bpmn:ScriptTask` với `scriptFormat="javascript"`. Backend vẫn là nơi quyết định cuối cùng.
- Nhãn loại phần tử: `bpmn:ScriptTask` là «Chạy script».
- `bpmn:ScriptTask` được tính là task, nên khối «Luật chuyển» và listener vẫn hiện. «Loại bước» bị ẩn, giống Service Task.
- Panel `ScriptTaskConfigPanel`:
  - ô script, font monospace, ghi chú «Giá trị của dòng cuối là kết quả»
  - bảng đầu vào: tên trong script và nguồn. Ô nguồn chứa `${` hoặc `#{` được ghi thành `sourceExpression`, ô khác thành `source`
  - ô biến kết quả
- Áp dụng panel ghi `scriptFormat="javascript"`, `script`, `flowable:resultVariable` (để trống thì xóa) và thay toàn bộ `flowable:In` trong `extensionElements`. Phần tử mở rộng khác được giữ.
- Lưu sơ đồ khi script trống, tên đầu vào sai dạng hoặc trùng, hay biến kết quả sai dạng: hiện cảnh báo và vẫn ghi BPMN. Backend sẽ từ chối, và message của backend được hiện ra.
- `flowableModdle` khai báo `resultVariable` và `autoStoreVariables` trên `bpmn:ScriptTask`, cùng kiểu `In` (`source`, `sourceExpression`, `target`). Nhờ vậy `sanitizeAttributes` không xóa các thuộc tính này.

## Kiểm tra

`ScriptTaskDeploymentGuardTest`:

- nhận task hợp lệ có `source`, `sourceExpression` và `resultVariable`
- từ chối mọi trường hợp ở mục «Kiểm tra lúc deploy», mỗi trường hợp một assert
- cờ tắt thì từ chối, kể cả task hợp lệ
- tài liệu không có script thì luôn nhận, cả khi cờ tắt

`ScriptValuesTest`, chạy Rhino thuần, không cần engine:

- đổi đầu vào và kết quả theo hai bảng ở mục «Luồng chạy»
- `Long` ngoài `2^53`, `NaN`, function và độ sâu 33 thì lỗi

`WorkflowScriptTaskEngineTest`, engine Flowable trong bộ nhớ trên H2:

- `amount * qty` ghi `total` là `Long`
- biến process không khai báo trong `<flowable:in>` là `undefined` trong script
- `typeof execution`, `typeof Packages`, `typeof java` đều là `'undefined'`
- `java.lang.System.exit(0)` làm `ScriptTaskException`, process không được tạo
- `while (true) {}` dừng với `ScriptTaskException` trong khoảng hai lần giới hạn thời gian
- đệ quy vô hạn dừng với `ScriptTaskException`
- `({a: 1, b: [true, 'x']})` ghi một `Map`
- `throw new Error('bad')` làm `ScriptTaskException` có chữ `bad`
- `scriptFormat="groovy"` deploy thẳng vào engine (không qua guard) thì lỗi khi chạy

`WorkflowDefinitionServiceImplTest`: deploy gọi guard trước validator của Agent và trước `createDeployment`.

Web:

- `scriptTaskPanel.spec.ts`: hàng đầu vào đổi qua lại với `flowable:In`; cảnh báo khi script trống, tên đầu vào sai hoặc trùng, biến kết quả sai
- `serviceTaskPanel.spec.ts`: «Loại bước» ẩn trên Script Task
- `bpmn.spec.ts`: XML xuất ra có `scriptFormat`, `flowable:resultVariable` và `flowable:in` sau khi qua `sanitizeAttributes`

## Câu hỏi còn mở

- Có cần một role EIM riêng, ví dụ `WF_SCRIPT_AUTHOR`, cho deploy có script hay không. Spec này giữ quyền deploy hiện tại.
- Rhino `1.7.14.1` do Flowable 8.0.0 chọn. Ngày 2026-11-04, OSV không ghi nhận lỗ hổng nào cho bản này. Advisory duy nhất của Rhino, `GHSA-3w8q-xq97-5j7x` (DoS qua `toFixed()`), đã được sửa đúng ở `1.7.14.1`. Cần kiểm tra lại mỗi lần nâng Flowable.
