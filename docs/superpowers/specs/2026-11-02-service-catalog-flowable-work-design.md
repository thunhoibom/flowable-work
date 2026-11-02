# Catalog dịch vụ theo Flowable Work

## Mục tiêu

Nâng màn Dịch vụ lên mức Service Registry của Flowable Work và gọi được mọi kiểu API đã chốt. Quy trình vẫn chỉ chọn dịch vụ và thao tác, rồi gán biểu thức. URL, xác thực và bí mật không nằm trên sơ đồ.

Thiết kế này mở rộng `2026-10-20-service-registry-design.md`. Những mục spec đó từng loại trừ nay nằm trong phạm vi: multipart, OAuth2 client credentials, thử lại, và nhập OpenAPI. Vẫn không gọi qua Agent hoặc MCP, không dán URL lên node BPMN, và không khóa một bản phát hành cũ trong sơ đồ.

## Cách làm

Một mô hình dữ liệu. Tám lát triển khai. Mỗi lát phát hành được. Lát sau không đổi hành vi của thao tác đã phát hành cho đến khi dịch vụ được phát hành lại.

1. Nền catalog.
2. Nội dung HTTP.
3. Xác thực mở rộng.
4. Phản hồi.
5. Hành vi lúc chạy.
6. Thử gọi.
7. Nhập OpenAPI.
8. Gọi nhiều bước.

## Mô hình

### Dịch vụ

- `serviceKey` bất biến, tên, mô tả.
- URL gốc theo môi trường: danh sách cặp tên môi trường và URL.
- Một xác thực dùng chung.

Engine có đúng một môi trường đang chạy, khóa cấu hình `workflow.services.environment`. Giá trị mặc định là `default`.

### Thao tác

- Mã, mô tả, phương thức, đường dẫn.
- Đường dẫn bắt đầu bằng `http://` hoặc `https://` là URL tuyệt đối. Đường dẫn còn lại nối vào URL gốc của môi trường đang chạy. Nối bằng đúng một dấu `/`.
- Chế độ xác thực: `INHERIT`, `OVERRIDE`, hoặc `NONE`. Thao tác mới mặc định `INHERIT`.
- Kiểu nội dung: `JSON`, `FORM`, `MULTIPART`, `RAW`. `RAW` có media type riêng. Mặc định của thao tác mới là `JSON`.
- Header tĩnh: cặp tên và giá trị chữ. Header lấy từ quy trình vẫn là tham số đầu vào.
- Gửi body: có nội dung, body rỗng, hoặc không gửi body.
- Đầu vào: tên, kiểu, bắt buộc, vị trí `PATH`, `QUERY`, `HEADER`, `BODY`.
- Đầu ra: tên, kiểu, nguồn `JSON_FIELD`, `RAW_BODY`, `STATUS`, hoặc `RESPONSE_HEADER`. Nguồn `JSON_FIELD` có tên field. Nguồn `RESPONSE_HEADER` có tên header.
- Quy tắc lỗi: danh sách có thứ tự. Mỗi quy tắc gồm các mẫu mã HTTP và một mã lỗi BPMN. Mẫu là một mã (`409`), một lớp (`5xx`), hoặc một khoảng (`400-499`).
- Mã thành công bổ sung, ngoài mọi mã `2xx`.
- `connectTimeoutMillis` và `readTimeoutMillis`.
- Chính sách thử lại, chuyển hướng và bỏ qua lỗi. Ba chính sách được lưu từ lát nền. Runtime chỉ thực thi chúng từ lát hành vi lúc chạy.

### Xác thực

Cùng một cấu trúc cho dịch vụ và cho thao tác tự khai báo.

- `NONE`.
- `BEARER`: token lưu cùng bản phát hành.
- `BEARER_VARIABLE`: tên biến quy trình. Giá trị đọc lúc gọi.
- `BASIC`: chuỗi `username:password` lưu cùng bản phát hành.
- `API_KEY`: tên header hoặc tên query, vị trí `HEADER` hoặc `QUERY`, và khóa đã lưu.
- `OAUTH2_CLIENT`: token URL, client id, client secret, danh sách scope.
- `MTLS`: chứng chỉ và khóa riêng dạng PEM, lưu mã hóa.
- `HMAC`: thuật toán `HmacSHA256`, tên header chữ ký. Chuỗi ký là method, đường dẫn, và body, ngăn bởi ký tự xuống dòng.
- `AWS_SIG_V4`: access key, secret, region, tên service AWS.

Bí mật mã hóa khi lưu. API catalog không trả lại bí mật. Ô khóa để trống nghĩa là giữ bí mật đã lưu. Đổi khóa chỉ có hiệu lực sau lần phát hành tiếp theo.

Header xác thực được ghi sau header tĩnh. Cùng tên thì giá trị xác thực thắng.

### Kiểu nội dung

- `JSON`: body mẫu là một object JSON. Tham số `BODY` ghi đè một field. Content-Type mặc định `application/json`.
- `FORM`: tham số `BODY` là field `application/x-www-form-urlencoded`.
- `MULTIPART`: tham số `BODY` là một phần, dạng `TEXT` hoặc `FILE`. `TEXT` gửi chữ. `FILE` gửi byte lấy từ biến quy trình.
- `RAW`: body là mẫu chữ hoặc nhị phân. Không có tham số `BODY`.

Body rỗng vẫn gửi phần thân rỗng. Không gửi body thì request không có phần thân.

### Đọc dữ liệu cũ

- URL hiện tại là URL tuyệt đối.
- Xác thực hiện tại là `OVERRIDE`.
- Một danh sách mã lỗi và một mã BPMN thành một quy tắc.
- `timeoutMillis` hiện tại ghi vào cả chờ kết nối và chờ đọc.
- Đầu ra field JSON giữ nguồn `JSON_FIELD`.
- Kiểu nội dung là `JSON`.

## Luồng một lần gọi

Runtime chỉ đọc bản đã phát hành.

1. Service Task mang mã dịch vụ, mã thao tác, biểu thức đầu vào và tên biến đầu ra.
2. Không có bản phát hành, hoặc không có thao tác trong bản đó: `SERVICE_NOT_PUBLISHED`.
3. Không có URL gốc cho môi trường đang chạy: `SERVICE_NOT_PUBLISHED`. URL tuyệt đối không cần URL gốc.
4. Chọn xác thực theo chế độ thao tác. `INHERIT` dùng xác thực của dịch vụ trong cùng bản phát hành.
5. Dựng request theo kiểu nội dung, header tĩnh và tham số. Placeholder URL còn sót, body JSON không phải object, hoặc biến Bearer thiếu: lỗi tham số như hiện tại, không phải `BpmnError`.
6. Gửi request.
7. Mã `2xx` hoặc mã nằm trong danh sách thành công: ghi đầu ra. Body không phải JSON thì bỏ qua đầu ra `JSON_FIELD`. `RAW_BODY`, `STATUS` và `RESPONSE_HEADER` vẫn ghi được.
8. Quy tắc lỗi khớp đầu tiên: ném mã BPMN của quy tắc đó.
9. Mã còn lại: `HTTP_ERROR`.
10. Hết thời gian hoặc mất kết nối: `SERVICE_UNAVAILABLE`.

Trước lát hành vi lúc chạy, runtime không thử lại, đi theo chuyển hướng mặc định của HTTP client, và không bỏ qua lỗi. Bí mật không vào biến quy trình và không vào log.

### Chính sách lúc chạy

Từ lát hành vi lúc chạy:

- Thử lại: số lần tối đa, các mã được thử lại, và khoảng chờ giữa các lần. Số lần tối đa bằng 1 nghĩa là không thử lại. Mặc định của thao tác mới là 1.
- Chuyển hướng: bật hoặc tắt. Mặc định bật.
- Bỏ qua lỗi: khi bật, lỗi HTTP và lỗi mạng không thành `BpmnError`. Đầu ra đọc được vẫn ghi. Đầu ra không đọc được để trống. Mặc định tắt.

Token OAuth2 được giữ đến trước hạn 30 giây. Lấy token thất bại thành `SERVICE_UNAVAILABLE`.

## Màn Dịch vụ

Màn hoàn chỉnh có sau đủ tám lát. Mỗi lát chỉ thêm phần của lát đó.

Hai cột. Cột trái là danh sách dịch vụ. Cột phải là dịch vụ đang chọn. Khối dài nằm trong modal hoặc vùng cuộn của cột phải.

Đầu cột phải: tên, mã, mô tả, nút Sửa dịch vụ, Xóa dịch vụ, Phát hành, Nhập OpenAPI. Modal sửa dịch vụ gồm tên, mô tả, bảng URL gốc, và xác thực dùng chung. Mã dịch vụ không sửa.

Bảng thao tác: STT, phương thức, mã, đường dẫn, kiểu nội dung, trạng thái phát hành, Sửa, Xóa. Modal sửa thao tác gồm Đường dẫn, Nội dung, Header tĩnh, Xác thực, Đầu vào, Đầu ra, Lỗi, Chính sách chạy.

Thử gọi nằm trong modal thao tác. Nó gửi dữ liệu đang mở trên form, kể cả sửa chưa lưu. Khóa để trống thì dùng khóa đã lưu của thao tác đó. Thử gọi không tạo quy trình và không ghi biến quy trình. Kết quả hiện mã trạng thái, header và body.

Nhập OpenAPI nhận JSON hoặc YAML bản OpenAPI 3. Có `operationId` chỉ gồm chữ, số, `_` và `-`, và bắt đầu bằng chữ, thì dùng làm mã thao tác. Không đủ điều kiện đó thì mã là method viết thường, rồi từng đoạn đường dẫn, ký tự khác chữ và số đổi thành `_`. Thao tác trùng mã thì cập nhật nháp. Thao tác không có trong file thì giữ nguyên. Không xóa và không phát hành.

Service Task trên sơ đồ vẫn chỉ chọn dịch vụ, thao tác, biểu thức và biến. Kiểu nội dung và nguồn đầu ra hiện chỉ đọc. Không có ô URL.

## Lát triển khai

1. **Nền catalog.** URL gốc, mô tả, xác thực chung với `NONE`, `BEARER`, `BASIC`, `API_KEY` trên header, đường dẫn tuyệt đối hoặc tương đối, nhiều quy tắc lỗi, mã thành công bổ sung, hai thời gian chờ. Màn hiện các mục này. Gọi JSON cũ vẫn đúng.
2. **Nội dung HTTP.** `FORM`, `MULTIPART`, `RAW`, header tĩnh, API key trên query, body rỗng và không gửi body.
3. **Xác thực mở rộng.** `OAUTH2_CLIENT`, `MTLS`, `BEARER_VARIABLE`, `HMAC`, `AWS_SIG_V4`.
4. **Phản hồi.** `RAW_BODY`, `STATUS`, `RESPONSE_HEADER`, văn bản, XML và file. Panel sơ đồ hiện nguồn đầu ra.
5. **Hành vi lúc chạy.** Thử lại, chuyển hướng, bỏ qua lỗi.
6. **Thử gọi.**
7. **Nhập OpenAPI.**
8. **Gọi nhiều bước.** Phân trang `NEXT_URL` hoặc `NEXT_TOKEN`, tối đa 10 trang theo mặc định. `NEXT_URL` nhận URL tuyệt đối hoặc URL tương đối so với request vừa gửi. `NEXT_TOKEN` ghi giá trị vào một tham số đầu vào đã chỉ định. Đầu ra là mảng JSON thì nối các trang. Đầu ra còn lại giữ trang cuối. Cookie chỉ sống trong một lần chạy Service Task, gồm các trang của lần đó. Chờ webhook: thao tác đồng bộ hoặc callback. Callback cần một đầu ra làm mã đối soát và một `waitTimeoutMillis`. Hết hạn thành `SERVICE_UNAVAILABLE`. Hệ thống nhận callback tại `POST /service-callbacks/{correlationId}`. Khớp mã thì hoàn thành task và dùng body callback như body phản hồi.

## Kiểm tra

Mỗi lát có ba lớp: runtime với bản đã phát hành, API catalog cho nháp và phát hành, và màn Dịch vụ cho trường của lát đó. Lát sau không làm hỏng bài kiểm tra lát trước.

Từ lát nền, các bài sau luôn đúng:

- Thao tác JSON đã phát hành, URL tuyệt đối, xác thực riêng, vẫn gửi đúng method, header và body.
- Thiếu bản phát hành, thiếu thao tác, hoặc thiếu URL gốc của môi trường: `SERVICE_NOT_PUBLISHED`.
- Hết thời gian hoặc mất kết nối: `SERVICE_UNAVAILABLE`.
- Mã không khớp quy tắc và không phải thành công: `HTTP_ERROR`.
- Bí mật không có trong biến quy trình hay log.

Lát thử gọi chứng minh request mẫu không tạo quy trình. Lát OpenAPI chứng minh file chỉ tạo hoặc cập nhật nháp.
