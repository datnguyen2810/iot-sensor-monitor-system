# IoT Sensor Monitor System — chạy và kiểm thử

- Java 21 / Spring Boot 4.1.1, MySQL/JPA, JWT/BCrypt, MQTT/Paho/Mosquitto.
- Frontend HTML/CSS/JavaScript thuần, Chart.js CDN; không cần npm/build.
- ESP8266 + DHT11 + ánh sáng analog; ba đầu ra led/fan/ac.
- Backend giai đoạn 1–6 và frontend giai đoạn 7 đã triển khai. Giai đoạn 8 có kiểm thử tự động; xác nhận ESP thật vẫn phải thực hiện theo [checklist phần cứng](../docs/HARDWARE_TEST.md).

## Chạy local

- Dùng một `src/main/resources/application.properties`, không tách dev/test profile, không bắt buộc biến môi trường.
- Kiểm tra các khóa DB `spring.datasource.url/username/password`, JWT `jwt.secret/expiration`, MQTT `mqtt.enabled/broker.url/client.id/username/password` trực tiếp trên máy. Không chia sẻ giá trị trong log/tài liệu.
- Khởi động MySQL, database trong datasource URL phải tồn tại. Hibernate update schema, seed INSERT IGNORE không thay mật khẩu tài khoản đã có.
- Giữ năm bảng user/sensors/datasensors/devices/history. Không có measurement_id hoặc migration 004; không tự xóa schema cũ.
- Mở Mosquitto bằng cấu hình broker đang dùng: `mosquitto -c PATH_TO_BROKER_CONFIG -v`. Repo không có cấu hình broker; cần listener/auth cho cả backend và ESP. Không dùng broker test tạm để test ESP thật.
- Trong `backend/`:

```powershell
.\mvnw.cmd spring-boot:run
```

- Nếu chỉ xem REST khi chưa có broker: ` .\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--mqtt.enabled=false'`. Điều khiển trả 503 trong chế độ này.
- Từ gốc repo, mở terminal khác:

```powershell
python -m http.server 5500 --directory frontend
```

- Mở http://localhost:5500/index.html; backend frontend trỏ http://localhost:8080. Có thể dùng VS Code Live Server; không dùng file://.
- Đăng nhập bằng tài khoản hiện có trong DB; không ghi mật khẩu seed ở đây. TODO: xác nhận tài khoản/mật khẩu local nếu chưa đăng nhập được.
- CORS cho HTTP localhost/127.0.0.1 ở port local; xem Swagger bằng đường dẫn cấu hình `springdoc.swagger-ui.path`.
- Frontend `API_CONFIG.DEMO_MODE=false` mặc định. Bật true trong `frontend/js/api.js` rồi chọn nút Xem dữ liệu mẫu trên trang login; không cần tài khoản thật, không gọi API, khóa điều khiển. Logout demo về login bình thường. Trước test ESP phải để false.
- Offline dashboard giữ số mẫu cố định có nhãn. Backend trả rỗng thì hiển thị --/chart rỗng. Lỗi bảng/auth không tự thay bằng demo; số 0 thật vẫn hiển thị.

## API và vòng đời lệnh

- Response luôn `{status, message, data}`, status số khớp HTTP; data null được giữ. Lỗi validation 400 có map trong data.
- `POST /api/auth/login`: body username/password; data có token/username. API còn lại gửi `Authorization: Bearer <JWT>`; thiếu/sai token 401, thiếu quyền 403.
- `GET /api/sensors/latest`: nhiệt độ/độ ẩm/ánh sáng/timestamp hoặc null; `/chart`: tối đa 15 mẫu đầy đủ, cũ → mới.
- `GET /api/sensors/history`: page, limit, sensor_type (Nhiệt Độ/Độ Ẩm/Ánh Sáng), search, search_type (time/value/rỗng), sort (asc/desc).
- `GET /api/devices/status`: device, status (ON/OFF/UNKNOWN), last_seen=null. Đây là trạng thái xác nhận cuối, không phải heartbeat/online; không có giờ ACK trong schema.
- `POST /api/devices/control`: body `{"device":"led","action":"ON"}` (led/fan/ac, ON/OFF). User lấy từ JWT, không từ frontend.
- HTTP 202 trả data `{"command_id":12,"device":"led","action":"ON","command_status":"pending"}`. 202 chưa phải phần cứng thành công.
- Một pending/thiết bị; xung đột 409, MQTT offline/lỗi publish 503. Lưu pending trước publish; ACK đúng ID mới kết thúc. Timeout mặc định 5 giây, scheduler quét mỗi 1 giây.
- `GET /api/devices/history`: page, limit, device (Đèn LED/Quạt/Điều hoà), action (ON/OFF), status (ACK ON/OFF), command_status (success/failed/pending), search (username/thời gian), sort.
- History có id/command_id/device_name/action_sent/status_received/command_status/operator/executed_at. Chưa ACK: status_received=null; user null: Hệ thống (Auto). executed_at là giờ tạo lệnh, không phải giờ ACK.
- PageResponse nằm trong data: data/total_records/total_pages/current_page; page ≥ 1, limit 1–100, search ≤ 100. Rỗng: [], 0 bản ghi, 1 trang; không tự trả trang đầu nếu page vượt cuối.
- Timestamp ISO +07:00, Asia/Ho_Chi_Minh; frontend format thời gian cùng múi giờ.

## MQTT / firmware

- ESP → `iot/sensor/data`: `{"temperature":28.5,"humidity":65,"light":534}`.
- Backend → `iot/control`: `{"device":"led","cmd":"ON","command_id":12}`; UTF-8 JSON, QoS 1, retained=false.
- ESP → `iot/status`: `{"device":"led","status":"ON","command_id":12}`. Firmware mới echo đúng ID số nguyên dương; backend bỏ ACK thiếu ID/trùng/trễ/sai device.
- ACK cũng hỗ trợ success/failed/pending và device_status ON/OFF tùy chọn. Kết quả lệnh khác ON/OFF của thiết bị.
- Payload cảm biến phải là số hữu hạn; humidity 0–100, light ≥ 0. Ba dòng lưu chung created_at trong một transaction; không ghi dở dang. QoS 1 có thể tạo bản ghi lặp vì giữ ERD không có khóa mẫu.
- Backend retry kết nối/subscription mỗi 5 giây. Firmware gốc ở `../sketch_aug25a.ino`; phải compile/nạp bản có command_id, không chỉ cắm USB.
- Lưu ý: firmware map ADC 0–1023 thành 0–1000, chưa có công thức hiệu chuẩn lux. Nhãn Lux hiện tại của UI/seed không chứng minh độ rọi vật lý.

## Kiểm thử

- Backend thường, trong backend: `.\mvnw.cmd test`. H2 khai báo trong annotation, không dùng profile hoặc DB chính. MySQL/MQTT/HTTP frontend test có cờ bật riêng.
- JavaScript, từ gốc (Node 24 đã kiểm chứng, không dependency npm):

```powershell
node --test --test-isolation=none frontend/tests/frontend.test.mjs
```

- Toàn bộ backend, MySQL schema tạm, broker tạm và frontend HTTP thật:

```powershell
.\mvnw.cmd '-Diot.mysql.tests=true' '-Diot.mqtt.tests=true' '-Diot.frontend.tests=true' '-Diot.mqtt.executable=PATH_TO_MOSQUITTO' verify
```

- Mosquitto trên PATH thì bỏ executable; Node trên PATH hoặc thêm `-Diot.frontend.node=PATH_TO_NODE`. MySQL cần quyền tạo/xóa schema tạm; không đổi URL test sang DB chính.
- Thêm `'-Diot.frontend.browser=PATH_TO_CHROME_OR_EDGE'` để smoke test native headless browser, profile/cổng/server tạm. Chart CDN được thay bằng test double chỉ trong HTTP test; hình thức Chart.js thực cần xem trên trình duyệt chạy local.
- Test flow bao gồm frontend login/JWT/CORS → nhận sensor MQTT → control/ACK → lọc sensor/history → logout. Firmware test là giả lập, không thay thế kiểm chứng GPIO/thiết bị thật.
- MySQL DATE_FORMAT chạy trên MySQL thật; H2 có alias riêng cho test. Test dọn schema/process/profile do nó tạo, không tác động main DB/broker/profile trình duyệt của người dùng.
- Windows sandbox có thể chặn headless browser; cần quyền chạy process tương ứng hoặc bỏ browser flag và thực hiện checklist UI thủ công.

## DB cũ và giới hạn

- `sql/normalize-command-status.sql` là script thủ công cho DB cũ: backup, dừng backend, xác nhận có history.status_received rồi mới SOURCE script trong MySQL. Không chạy tự động; TODO: xác nhận DB chính đã normalize chưa.
- Hibernate update không tự xóa bảng/cột thừa từ phiên bản cũ; kiểm tra/backup và xin xác nhận trước khi dọn.
- Không có giờ ACK/heartbeat/failure_reason nên failed không chỉ ra riêng timeout hay lỗi publish, trạng thái sau reboot có thể cũ.
- Chart bỏ timestamp trùng/thiếu sensor để tránh ghép nhầm mẫu; history vẫn giữ dòng gốc.
