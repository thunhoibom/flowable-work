# Checklist cấu hình EIM / Keycloak cho Workflow Agent

Dành cho team quản trị EIM và Keycloak (realm `rsa`). Làm trên **UAT** trước, kiểm tra xong mới lặp lại trên
**prod**.

Thiết kế chi tiết: [2026-11-03-eim-sitemap-authorization-design.md](2026-11-03-eim-sitemap-authorization-design.md).

| Môi trường | Keycloak / EIM |
|---|---|
| UAT | `https://uat-auth-sso.vetc.com.vn`, realm `rsa` |
| Prod | `https://auth-sso.vetc.com.vn`, realm `rsa` |

Workflow Agent làm **giống hệt rsa-core-service**:

1. **Đăng nhập** bằng Keycloak (password grant). Không có tài khoản local, không đăng ký, không đổi mật khẩu.
2. **Quyền gọi API** lấy từ claim **`eim_roles`** của access token. Mỗi API ghi cứng danh sách vai trò được
   phép (`hasAnyRole('WF_ADMIN', ...)`).
3. **Menu** lấy từ sitemap EIM (`/realms/rsa/auth/sitemaps-for-role-prl`, app code `VETC.WORKFLOW`).

> Vai trò chỉ đến từ EIM. Mã vai trò được **ghi cứng trong code**, nên phải đặt **đúng chính tả** như phần D.
> Đổi tên vai trò trên EIM đồng nghĩa với việc sửa code.

---

## Phần A. Thống nhất trước khi cấu hình

Đã chốt:

- **App code và client id: `VETC.WORKFLOW`.** Ứng dụng hoàn toàn mới, không dùng chung client, app code, sitemap
  hay vai trò với `VETC.RSA`.
- **Bốn vai trò cố định:** `WF_PLATFORM_ADMIN`, `WF_ADMIN`, `WF_OPERATOR`, `WF_VIEWER` (phần D).

Cần team EIM trả lời:

- [ ] **A1.** Claim `eim_roles` trong token của client `VETC.WORKFLOW` có chứa vai trò `WF_*` của người dùng không
  (token của `VETC.RSA` đang chứa `RSA_ADMIN,RSA_PROVIDER,...`)? **Đây là điều kiện bắt buộc**: thiếu thì người
  dùng đăng nhập được nhưng không gọi được API nào.
- [ ] **A2.** Đổi vai trò của người dùng trên EIM thì token mới có hiệu lực khi nào: lần refresh kế tiếp (tối đa
  30 phút) hay phải đăng nhập lại?
- [ ] **A3.** Có giới hạn tần suất gọi `sitemaps-for-role-prl` không. Workflow Agent gọi khi mở ứng dụng và tối đa
  1 lần / 5 phút / người dùng.
- [ ] **A4.** Người chịu trách nhiệm gán và thu hồi vai trò Workflow Agent trên EIM (đặc biệt `WF_PLATFORM_ADMIN`).

## Phần B. Keycloak (realm `rsa`)

- [ ] **B1.** Tạo client `VETC.WORKFLOW`:
  - Client type: OpenID Connect
  - Client authentication: **On** (confidential)
  - Authentication flow: **Direct access grants: On** (bắt buộc, Workflow Agent đăng nhập bằng password grant)
  - Standard flow: Off (chưa dùng)
  - Service accounts: Off
- [ ] **B2.** Mapper / client scope **giống hệt client `VETC.RSA`**, để access token có đủ claim:

  | Claim | Bắt buộc | Dùng để |
  |---|---|---|
  | `preferred_username` | Có | Khớp tài khoản Workflow Agent |
  | `eim_roles` | **Có** | Vai trò `WF_*`, phân cách bằng dấu phẩy. Quyết định mọi quyền gọi API |
  | `transaction_id` | Có | Header `TRANS_ID` khi tải menu. Thiếu thì người dùng không có menu |
  | `name` | Nên có | Tên hiển thị |
  | `azp` | Tự có | Chỉ chấp nhận token phát cho `VETC.WORKFLOW` |

  Cách nhanh nhất: gán cùng các client scope và mapper mà `VETC.RSA` đang dùng.
- [ ] **B3.** Lấy client secret (tab *Credentials*). Gửi cho team Workflow Agent qua **kênh bảo mật** (không gửi
  qua chat/email thường).
- [ ] **B4.** Thời lượng token, giữ như realm hiện tại hoặc đặt riêng cho client:
  - Access Token Lifespan: hiện 30 phút. Vai trò nằm trong token, nên **thu hồi vai trò có hiệu lực tối đa sau
    thời lượng này** (xem A2).
  - SSO Session Idle: hiện 7 ngày (`refresh_expires_in = 604800`).
- [ ] **B5.** Đăng xuất: không cần cấu hình thêm. Workflow Agent gọi `/protocol/openid-connect/logout` bằng
  refresh token.

## Phần C. Ứng dụng và sitemap (menu) trên EIM

- [ ] **C1.** Tạo ứng dụng với app code `VETC.WORKFLOW`, tên hiển thị
  "Quy trình nghiệp vụ (Workflow Agent)".
- [ ] **C2.** Tạo cây sitemap. `strCode` phải **đúng chính tả và hoa thường** như bảng dưới: web dùng mã này để
  biết node nào ứng với màn hình nào. `strName` và `ord` được dùng làm **nhãn và thứ tự menu**. Trạng thái tất cả
  node: **hoạt động (`strStatus = 1`)**; node tắt sẽ ẩn cả các node con.

  | `ord` | `strCode` | Node cha | `strName` (gợi ý) | Màn hình |
  |---|---|---|---|---|
  | 1 | `WF_ROOT` | (gốc) | Quy trình nghiệp vụ | (nhóm menu) |
  | 1 | `WF_DEFINITION` | `WF_ROOT` | Quy trình | `/process-definitions` |
  | 2 | `WF_INSTANCE` | `WF_ROOT` | Công việc | `/process-instances` |
  | 3 | `WF_ASSIGNMENT` | `WF_ROOT` | Phân công | `/assignment-rules` |
  | 4 | `WF_SERVICE` | `WF_ROOT` | Dịch vụ | `/services` |
  | 5 | `WF_EVENT` | `WF_ROOT` | Sự kiện | `/events` |
  | 6 | `WF_AGENT` | `WF_ROOT` | Agent | `/agents` |
  | 7 | `WF_MEMBER` | `WF_ROOT` | Thành viên | `/access` |
  | 8 | `WF_TENANT` | `WF_ROOT` | Tổ chức | `/tenants` |

- [ ] **C3.** Privilege của node: gán `ACCESS` (hoặc `VIEW`) là đủ. Privilege **chỉ quyết định menu**, không quyết
  định quyền gọi API (quyền API theo vai trò, phần D).

## Phần D. Vai trò và gán quyền

- [ ] **D1.** Tạo **đúng** bốn vai trò sau (mã ghi cứng trong code):

  | Vai trò | Dành cho | Được làm trong Workflow Agent |
  |---|---|---|
  | `WF_VIEWER` | Theo dõi | Xem quy trình và công việc |
  | `WF_OPERATOR` | Người xử lý, phê duyệt | Như `WF_VIEWER`, thêm khởi tạo quy trình; duyệt, từ chối, chuyển **task được giao cho mình** |
  | `WF_ADMIN` | Người thiết kế, vận hành | Như `WF_OPERATOR`, thêm thiết kế/deploy quy trình, quy tắc phân công, dịch vụ, Agent |
  | `WF_PLATFORM_ADMIN` | Quản trị nền tảng (1–2 người) | Mọi thứ, thêm quản lý tổ chức và thành viên; **xem dữ liệu mọi tổ chức** |

- [ ] **D2.** Gán node sitemap cho từng vai trò để menu khớp với quyền. Nhớ gán `WF_ROOT` cho mọi vai trò.

  | Node | `WF_PLATFORM_ADMIN` | `WF_ADMIN` | `WF_OPERATOR` | `WF_VIEWER` |
  |---|---|---|---|---|
  | `WF_ROOT` | ✔ | ✔ | ✔ | ✔ |
  | `WF_DEFINITION` | ✔ | ✔ | ✔ | ✔ |
  | `WF_INSTANCE` | ✔ | ✔ | ✔ | ✔ |
  | `WF_ASSIGNMENT` | ✔ | ✔ | | |
  | `WF_SERVICE` | ✔ | ✔ | | |
  | `WF_EVENT` | ✔ | ✔ | ✔ | ✔ |
  | `WF_AGENT` | ✔ | ✔ | | |
  | `WF_MEMBER` | ✔ | | | |
  | `WF_TENANT` | ✔ | | | |

  `WF_EVENT` có cho `WF_OPERATOR` và `WF_VIEWER` (Đ1 lát 6): họ cần tab "Đang chờ" để xem công việc nào đang chờ
  sự kiện, và xem thư viện sự kiện ở chế độ chỉ đọc. Tạo, lưu, phát hành và xóa sự kiện hay kênh vẫn chỉ dành cho
  `WF_PLATFORM_ADMIN` và `WF_ADMIN`, cả trên web lẫn API.

  Gán thừa node (ví dụ `WF_SERVICE` cho `WF_VIEWER`) không mở thêm quyền: web vẫn ẩn màn hình vì vai trò không
  đủ, và API vẫn trả 403.
- [ ] **D3.** Gán vai trò cho người dùng. **Phải có ít nhất một người `WF_PLATFORM_ADMIN`**, vì Workflow Agent không
  có tài khoản quản trị local. Tối thiểu để kiểm thử trên UAT:
  - [ ] Một tài khoản `WF_PLATFORM_ADMIN`
  - [ ] Một tài khoản `WF_ADMIN`
  - [ ] Một tài khoản `WF_OPERATOR`
  - [ ] Một tài khoản `WF_VIEWER`
  - [ ] Một tài khoản **không** có vai trò `WF_*` nào (kiểm tra bị chặn)

  Có thể dùng `rsa_test1` cho một trong các vai trò trên.

## Phần E. Tự kiểm tra trước khi bàn giao

Chạy bằng Git Bash, thay `<...>` bằng giá trị thật.

- [ ] **E1.** Lấy token:
  ```bash
  KC=https://uat-auth-sso.vetc.com.vn/realms/rsa
  TOKEN=$(curl -s -X POST $KC/protocol/openid-connect/token \
    -d client_id=VETC.WORKFLOW -d client_secret=<CLIENT_SECRET> \
    -d grant_type=password -d username=<USER> --data-urlencode "password=<PASSWORD>" \
    | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
  ```
  Kết quả mong đợi: `TOKEN` bắt đầu bằng `eyJ`.
- [ ] **E2.** Kiểm tra claim:
  ```bash
  echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo
  ```
  Phải có `preferred_username`, `transaction_id`, `azp` = `VETC.WORKFLOW`, và **`eim_roles` chứa đúng vai trò
  `WF_*`** của tài khoản (ví dụ `"eim_roles":"WF_ADMIN"`).
- [ ] **E3.** Gọi sitemap với app code mới:
  ```bash
  TRANS=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null \
    | grep -oE '"transaction_id":"[^"]*"' | cut -d'"' -f4)
  curl -s "$KC/auth/sitemaps-for-role-prl" \
    -H "TRANS_ID: $TRANS" -H "APP_CODE: VETC.WORKFLOW" -H "Authorization: $TOKEN"
  ```
  Mong đợi: `"status":"1"`, cây chỉ gồm các node `WF_*` theo bảng D2.
- [ ] **E4.** Lặp lại E1–E3 cho từng tài khoản ở D3. Tài khoản không có vai trò: `eim_roles` không có `WF_*`, cây
  rỗng.

## Phần F. Bàn giao cho team Workflow Agent

- [ ] **F1.** Client secret của `VETC.WORKFLOW` (gửi qua kênh bảo mật).
- [ ] **F2.** Xác nhận app code `VETC.WORKFLOW`, sitemap và bốn vai trò đã tạo.
- [ ] **F3.** Danh sách tài khoản kiểm thử và vai trò tương ứng.
- [ ] **F4.** Câu trả lời cho A1–A4.

## Phần G. Prod

- [ ] **G1.** Lặp lại phần B–D trên prod với **cùng** mã vai trò và `strCode`.
- [ ] **G2.** Client secret prod khác UAT; gửi riêng qua kênh bảo mật.
- [ ] **G3.** Chạy lại phần E trên prod với `KC=https://auth-sso.vetc.com.vn/realms/rsa`.
- [ ] **G4.** Gán vai trò cho người dùng thật, trong đó có ít nhất một `WF_PLATFORM_ADMIN`.
