# Phân quyền và menu theo EIM, giống rsa-core-service

## Mục tiêu

Phân quyền người dùng của workflow-agent làm **giống hệt rsa-core-service**:

- Đăng nhập bằng Keycloak SSO, không có tài khoản local.
- Quyền gọi API theo vai trò trong claim `eim_roles` của access token; mỗi API ghi cứng danh sách vai trò bằng
  `@PreAuthorize("hasAnyRole(...)")`.
- Menu lấy từ sitemap EIM qua `GET /auth/sitemaps`.

Không có chế độ phân quyền local, không có quản trị viên local, không có đường dự phòng.

Checklist cấu hình phía EIM/Keycloak: [2026-11-03-eim-configuration-checklist.md](2026-11-03-eim-configuration-checklist.md).

## Quyết định

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Vai trò lấy từ claim `eim_roles` (chuỗi phân cách dấu phẩy), cấp thành `ROLE_<mã>` | Giống `SecurityConfig.extractAuthorities` của rsa-core-service |
| D2 | Ứng dụng mới `VETC.WORKFLOW`: client Keycloak = app code EIM; chỉ nhận token có `azp = VETC.WORKFLOW` | Tách khỏi portal RSA |
| D3 | Bốn vai trò cố định `WF_PLATFORM_ADMIN`, `WF_ADMIN`, `WF_OPERATOR`, `WF_VIEWER`, ghi cứng trong `@PreAuthorize` | Như RSA ghi cứng `RSA_ADMIN`, `RSA_OSA`… |
| D4 | Bỏ hoàn toàn đăng nhập local, đăng ký, đổi mật khẩu, admin bootstrap, token nội bộ, role/permission trong DB | EIM là nguồn duy nhất |
| D5 | Menu lấy từ sitemap EIM; web khớp `strCode` với màn hình, dùng `strName`/`ord` làm nhãn/thứ tự | Như portal RSA |
| D6 | Menu chỉ hiện màn hình có trong sitemap **và** vai trò đủ quyền | Không hiện màn hình mà API sẽ trả 403 |
| D7 | EIM sập chỉ mất menu; API vẫn chạy vì vai trò đã nằm trong token | Giống RSA |
| D8 | Membership tenant vẫn ở local, do `WF_PLATFORM_ADMIN` quản lý; đăng nhập lần đầu tự vào tenant `default` | EIM không có khái niệm tenant |
| D9 | Principal `SERVICE` (`X-Workflow-Token`) giữ nguyên | Xác thực hệ thống với hệ thống |

## Vai trò

| API / màn hình | Vai trò được phép |
|---|---|
| Xem quy trình, công việc (`/workflow/**` đọc) | `WF_PLATFORM_ADMIN`, `WF_ADMIN`, `WF_OPERATOR`, `WF_VIEWER` (+ `SERVICE`) |
| Khởi tạo quy trình, duyệt/từ chối/chuyển task | `WF_PLATFORM_ADMIN`, `WF_ADMIN`, `WF_OPERATOR` (+ `SERVICE`) |
| Thiết kế, deploy quy trình; quy tắc phân công | `WF_PLATFORM_ADMIN`, `WF_ADMIN` (+ `SERVICE`) |
| Danh mục dịch vụ, Agent | `WF_PLATFORM_ADMIN`, `WF_ADMIN` |
| Danh bạ người dùng (chọn người xử lý) | `WF_PLATFORM_ADMIN`, `WF_ADMIN`, `WF_OPERATOR` |
| Tổ chức, thành viên, sự kiện Agent, `/actuator/prometheus` | `WF_PLATFORM_ADMIN` |

`WF_PLATFORM_ADMIN` bỏ qua cách ly dữ liệu giữa tenant (RLS `app.platform_admin`). Kiểm tra assignee của Flowable
giữ nguyên: `WF_OPERATOR` chỉ thao tác task giao cho mình hoặc mình là candidate user.

Vai trò của ứng dụng khác (ví dụ `RSA_ADMIN`) vẫn được nạp như RSA làm, nhưng không API nào của workflow chấp
nhận chúng.

## Luồng

```
POST /auth/login  →  Keycloak password grant (client VETC.WORKFLOW)
                  →  cookie HttpOnly: access token + refresh token
Request (cookie / Bearer)
  → AuthTokenAuthenticationFilter
      1. Kiểm tra JWT (RS256, issuer, azp)
      2. eim_roles → ROLE_WF_ADMIN, ...
      3. Tài khoản local (tự tạo lần đầu), tenant theo header X-Tenant-Code, kiểm tra membership
  → @PreAuthorize("hasAnyRole(...)")
GET /auth/sitemaps → EIM sitemaps-for-role-prl (TRANS_ID, APP_CODE, Authorization = token thô)
                   → bỏ node strStatus != 1 (cả cây con), sắp theo ord
```

Lỗi sitemap (timeout, HTTP lỗi, `status != "1"`, thiếu `transaction_id`): `/auth/sitemaps` trả 503
`EIM_UNAVAILABLE`; web hiện "Không tải được menu từ EIM" kèm nút thử lại. Metric
`workflow_eim_sitemap_requests_total{outcome}`.

## Cấu hình

```yaml
workflow:
  auth:
    keycloak:
      server-url: ${WORKFLOW_KEYCLOAK_SERVER_URL:}       # bắt buộc
      realm: ${WORKFLOW_KEYCLOAK_REALM:rsa}
      client-id: ${WORKFLOW_KEYCLOAK_CLIENT_ID:}         # VETC.WORKFLOW, bắt buộc
      client-secret: ${WORKFLOW_KEYCLOAK_CLIENT_SECRET:}
      allowed-client-ids: ${WORKFLOW_KEYCLOAK_ALLOWED_CLIENT_IDS:}   # VETC.WORKFLOW
      roles-claim: eim_roles                             # mặc định
    eim:
      app-code: ${WORKFLOW_EIM_APP_CODE:VETC.WORKFLOW}
      sitemap-path: /auth/sitemaps-for-role-prl
```

Thiếu `server-url`, `realm` hoặc `client-id` thì ứng dụng không khởi động. Các biến cũ đã bỏ:
`WORKFLOW_KEYCLOAK_ENABLED`, `WORKFLOW_EIM_ENABLED`, `WORKFLOW_AUTH_TOKEN_SECRET`, `WORKFLOW_BOOTSTRAP_ADMIN_*`,
`WORKFLOW_SELF_REGISTRATION_*`.

## Đã triển khai (2026-11-03)

| Thành phần | Vị trí |
|---|---|
| Hằng số vai trò | `shared-kernel/.../context/WorkflowRoles` |
| Đọc `eim_roles`, `transaction_id` | `KeycloakJwtVerifier` |
| Filter | `AuthTokenAuthenticationFilter`: chỉ nhận token Keycloak, roles = `eim_roles` |
| Menu | port `MenuSitemapProvider`; `EimSitemapProvider`, `EimSitemapClient`; `GET /auth/sitemaps` |
| Thành viên | `AccessManagementController`: chỉ thêm, bật/tắt thành viên; không còn vai trò |
| Đã xoá | `AuthTokenService`, `PlatformAdminBootstrap`, self-registration, `PasswordHasher`, `AuthAuthorizationRepository` và API `/auth/register`, `/auth/me/password`, `/auth/switch-tenant`, `/auth/access/roles`, `/auth/access/permissions`, `/auth/access/members/{id}/roles` |
| Web | `features/auth/menu.ts` (`buildNavigation`), route `meta.navigation.menuCode`, `ROLES` trong `authorization.ts`; bỏ đăng ký, đổi mật khẩu, tab Vai trò |
| Kiểm thử | unit trong `auth-engine`; tích hợp `AuthenticationHttpIntegrationTest` với `FakeKeycloak` (JWK set, password grant, sitemap) và Postgres thật |

Bảng `auth_role`, `auth_user_role`, `auth_role_permission`, `auth_permission` không còn được dùng nhưng chưa xoá
khỏi DB.

HTTP client gọi Keycloak và EIM dùng `JdkClientHttpRequestFactory`: `HttpURLConnection` không đọc được body của
phản hồi 401, khiến sai mật khẩu (`invalid_grant`) bị báo nhầm là lỗi cấu hình (500).

## Câu hỏi mở cho team EIM

Xem checklist phần A: `eim_roles` của client `VETC.WORKFLOW` có chứa vai trò `WF_*` không (bắt buộc), thời điểm
thay đổi vai trò có hiệu lực, giới hạn tần suất sitemap, người chịu trách nhiệm gán vai trò.
