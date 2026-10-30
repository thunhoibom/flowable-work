# Giao diện danh mục dịch vụ

## Mục tiêu

Màn Dịch vụ dùng được như một catalog: chọn dịch vụ ở cột trái, xem và sửa thao tác ở cột phải. Form chia theo việc người dùng đang làm. Trạng thái nháp và đã phát hành đọc được ngay trên danh sách.

API không đổi. Màn vẫn tạo dịch vụ, lưu thao tác và phát hành.

## Phạm vi

Có:

- Route `/services` giữ nguyên. Menu vẫn là **Dịch vụ**.
- `PageHeader`: nhãn **Tích hợp**, tiêu đề **Dịch vụ**, mô tả ngắn rằng quy trình chỉ chọn dịch vụ và thao tác.
- Hộp thoại **Tạo dịch vụ** với mã và tên.
- Cột trái: tìm theo mã hoặc tên, danh sách dịch vụ, nhãn khi còn việc chưa phát hành.
- Cột phải: tiêu đề dịch vụ, nút **Phát hành**, bảng thao tác, form sửa.
- Form năm phần: Yêu cầu, Xác thực, Lỗi, Đầu vào, Đầu ra. Cùng các trường API đang nhận.
- Nhãn thao tác: **Chưa phát hành**, **Có thay đổi**, **Đã phát hành**.
- Chặn lưu khi thiếu dữ liệu bắt buộc. Lỗi API vẫn hiện bằng thông báo hiện có.
- Trạng thái trống và đang tải.

Không có:

- API mới, xóa, đổi tên, gọi thử, header tùy chỉnh, nhập OpenAPI.
- Phân trang. Danh sách là toàn bộ kết quả `GET /services`.
- So sánh xác thực, mẫu nội dung, thời gian chờ hoặc danh sách mã HTTP lỗi với bản đã phát hành. API không trả các trường đó của snapshot.

## Bố cục

Trang là hai cột. Cột trái rộng khoảng 300px. Cột phải chiếm phần còn lại.

Dưới 960px hai cột xếp dọc, danh sách ở trên.

Khi danh sách tải xong và chưa chọn gì, chọn dịch vụ đầu tiên. Sau khi tạo, chọn đúng dịch vụ vừa tạo và mở form thao tác trống.

## Danh sách dịch vụ

Ô tìm lọc không phân biệt hoa thường trên `serviceKey` và `name` của dữ liệu đã tải. Xóa ô tìm thì hiện lại toàn bộ.

Mỗi dòng hiện tên, mã, số thao tác. Nếu có ít nhất một thao tác **Chưa phát hành** hoặc **Có thay đổi**, dòng có nhãn **Cần phát hành**.

Danh sách catalog trống: trạng thái trống và nút tạo. Ô tìm không khớp dòng nào: thông báo không có kết quả, phần chi tiết của dịch vụ đang chọn vẫn giữ nguyên.

Hộp thoại tạo:

- Mã và tên đều bắt buộc sau khi cắt khoảng trắng.
- Mã đã có trong danh sách đã tải thì không gửi, và nói mã đã tồn tại.
- Mã khóa sau khi tạo. Màn này không sửa tên dịch vụ đã tạo, vì API không có đổi tên.

## Bảng thao tác

Cột: phương thức, mã thao tác, mẫu địa chỉ, trạng thái, khóa đã lưu hay chưa.

Bấm một dòng thì đổ form sửa. **Thêm thao tác** xóa form về thao tác mới.

Mã thao tác chỉ nhập khi tạo. Khi sửa, mã khóa. Đổi mã trên form hiện tại sẽ `PUT` sang khóa khác và không xóa thao tác cũ; khóa mã để tránh ghi đè nhầm.

Dịch vụ chưa có thao tác: trạng thái trống trong bảng, form vẫn mở để thêm thao tác đầu tiên.

## Form thao tác

1. **Yêu cầu.** Phương thức `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. Mẫu địa chỉ. Mẫu nội dung là vùng nhiều dòng. Thời gian chờ là số nguyên, tối thiểu 1 mili giây. Mặc định phương thức `POST`, thời gian chờ `5000`, mã lỗi `HTTP_ERROR`, mã trạng thái lỗi `409,5XX`.
2. **Xác thực.** Không, Bearer, Basic, API Key. API Key thì hiện ô header, mặc định gợi ý `X-Api-Key`. Ô khóa là mật khẩu. Để trống khi lưu thì giữ khóa đã lưu. Nếu `secretPresent` thì nói đã có khóa.
3. **Lỗi.** Ô mã trạng thái, cách nhau bởi dấu phẩy. Ô mã lỗi BPMN, để trống thì gửi `HTTP_ERROR`.
4. **Đầu vào.** Tên, kiểu, vị trí, bắt buộc. Kiểu: `STRING`, `INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN`, `JSON`. Vị trí: `PATH`, `QUERY`, `HEADER`, `BODY`. Vị trí `PATH` luôn bắt buộc và không bỏ chọn, theo `pathRequirement`.
5. **Đầu ra.** Tên, kiểu, trường JSON.

**Lưu thao tác** gửi đúng payload hiện có. Sau khi lưu, xóa ô khóa trên form, tải lại danh sách, giữ dịch vụ và thao tác vừa lưu.

Thiếu một trong các mục sau thì không gọi API, và nêu đúng mục đó:

- Mã thao tác, mẫu địa chỉ.
- Header API key khi loại xác thực là API Key.
- Tên tham số đầu vào hoặc đầu ra đang có trên form.
- Trường JSON của đầu ra đang có trên form.

## Phát hành

Nút **Phát hành** nằm ở đầu cột phải và phát hành cả dịch vụ.

Form tính là chưa lưu khi người dùng đã sửa một trường so với thao tác đang mở, hoặc đã nhập liệu trên form thêm mới. Form thêm mới còn nguyên mặc định, chưa gõ gì, không chặn phát hành.

Form chưa lưu thì **Phát hành** không gọi API. Thông báo yêu cầu lưu thao tác trước. Chọn dịch vụ khác, chọn thao tác khác, hoặc **Thêm thao tác** khi form chưa lưu thì hỏi hủy thay đổi. Hủy thì chuyển. Ở lại thì giữ form.

**Phát hành** không hỏi lại. Thành công vẫn báo những lần chạy sau dùng bản này.

Dịch vụ không có thao tác vẫn bấm được. API chấp nhận phát hành danh sách rỗng.

## Nhãn trạng thái

Hàm thuần so sánh nháp với các trường snapshot mà API trả về.

| Điều kiện | Nhãn |
| --- | --- |
| Không có `publishedUrl` | Chưa phát hành |
| `method`, `urlTemplate`, `bpmnErrorCode`, `inputParameters` hoặc `outputParameters` khác bản đã phát hành tương ứng | Có thay đổi |
| Các trường trên trùng | Đã phát hành |

So sánh tham số theo tên, kiểu, bắt buộc, vị trí với đầu vào đã phát hành; theo tên, kiểu, trường JSON với đầu ra đã phát hành. Thứ tự khác nhau nhưng cùng nội dung vẫn là trùng. Thiếu `publishedMethod` hoặc danh sách tham số đã phát hành được coi là khác khi nháp có giá trị.

Đổi xác thực, khóa, mẫu nội dung, thời gian chờ hoặc mã trạng thái lỗi không tự thành **Có thay đổi**.

## Thành phần

| Thành phần | Việc |
| --- | --- |
| `ServiceCatalogView.vue` | Trang, chọn dịch vụ, hộp thoại tạo, phát hành, gọi API |
| `ServiceListPanel.vue` | Ô tìm và danh sách |
| `OperationTable.vue` | Bảng thao tác và nút thêm |
| `OperationEditor.vue` | Năm phần của form |
| `catalogStatus.ts` | Nhãn thao tác và nhãn **Cần phát hành** của dịch vụ |

`api.ts` và `parameterDraft.ts` giữ nguyên. Style dùng token trong `design-system.css`. Không thêm màu riêng cho trang này.

## Kiểm tra

- Chọn thao tác thì form nhận đúng mẫu nội dung, và tham số `PATH` bị khóa.
- Ba nhãn trạng thái đúng với dữ liệu giả, gồm hai danh sách tham số cùng nội dung nhưng khác thứ tự.
- Đổi mẫu nội dung không làm nhãn thành **Có thay đổi**.
- Danh sách trống và ô tìm không khớp vẫn hiện thông báo.
- Lưu bị chặn khi thiếu địa chỉ hoặc thiếu header API key, và API không bị gọi.
- Mã thao tác không sửa được khi đang sửa thao tác đã có.
- Form thêm mới chưa gõ gì không chặn phát hành. Form đã sửa thì chặn phát hành và hỏi trước khi đổi thao tác.
