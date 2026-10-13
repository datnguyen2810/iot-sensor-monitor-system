# IoT Sensor Monitor System

Hệ thống giám sát nhiệt độ, độ ẩm, ánh sáng và điều khiển LED/quạt/điều hòa bằng ESP8266. Thiết bị trao đổi dữ liệu với backend qua MQTT; giao diện web lấy dữ liệu và gửi yêu cầu điều khiển qua REST API có xác thực JWT.

## Chức năng

- Đăng nhập bằng tài khoản trong MySQL, mật khẩu lưu dưới dạng BCrypt.
- Dashboard hiển thị số đo mới nhất và biểu đồ tối đa 15 mẫu đầy đủ.
- Tra cứu dữ liệu cảm biến theo tiêu chí, từ khóa, thứ tự và phân trang.
- Điều khiển ba đầu ra `led`, `fan`, `ac`; theo dõi phản hồi thiết bị bằng `command_id`.
- Xem lịch sử điều khiển với hành động `ON`/`OFF` và kết quả `success`/`failed`/`pending`.
- Frontend chỉ sử dụng backend thật, không có dữ liệu demo hoặc mô phỏng thành công khi mất kết nối.

## Công nghệ và phần cứng

- Backend: Java 21, Spring Boot 4.1.1, Spring Data JPA, MySQL, Spring Security, JWT, Eclipse Paho MQTT.
- Frontend: HTML/CSS/JavaScript thuần, Chart.js qua CDN; không cần npm hoặc bước build.
- Broker: Mosquitto.
- Firmware: Arduino ESP8266, DHT11, cảm biến ánh sáng analog; thư viện DHT, PubSubClient và ArduinoJson.
- Kiểm thử: JUnit/Spring Boot, H2, MySQL và MQTT integration test; Node.js và browser smoke test.

Các chân trong sketch hiện tại:

| Thành phần | Chân ESP8266 |
| --- | --- |
| DHT11 | D4 |
| Ánh sáng | A0 |
| LED | D1 |
| Quạt | D2 |
| Điều hòa | D5 |

Các đầu ra là tín hiệu GPIO. Muốn điều khiển tải thật cần mạch driver/relay và nguồn phù hợp; không nối tải trực tiếp vào GPIO.

## Cấu trúc repo

```text
.
├── README.md
├── backend/
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   ├── sql/                         # Script chuyển đổi DB cũ, chạy thủ công
│   └── src/
│       ├── main/java/com/iot/backend/
│       │   ├── config/              # Security, CORS, MQTT
│       │   ├── controller/          # REST API
│       │   ├── dto/                 # Request, response, payload MQTT
│       │   ├── entity/              # User, Sensor, DataSensor, Device, History
│       │   ├── exception/           # Chuẩn hóa phản hồi lỗi
│       │   ├── repository/          # JpaRepository và query khai báo
│       │   ├── scheduler/           # Quét lệnh hết hạn
│       │   ├── security/            # JWT và xác thực
│       │   └── service/             # Auth, sensor, device, history, MQTT
│       ├── main/resources/          # Cấu hình và seed DB
│       └── test/                    # Kiểm thử backend
├── frontend/
│   ├── index.html                   # Đăng nhập
│   ├── dashboard.html
│   ├── data-sensor.html
│   ├── history.html
│   ├── profile.html
│   ├── css/ / js/ / assets/
│   └── tests/
└── sketch_aug25a.ino                # Firmware ESP8266
```

## Luồng hoạt động

### Thu thập cảm biến

```text
ESP đọc cảm biến → Mosquitto: iot/sensor/data
  → MqttService nhận tin, giải mã UTF-8 và kiểm tra JSON
  → SensorService lưu ba số đo trong một transaction
  → MySQL → API latest/chart/history → frontend
```

- Sketch gửi mẫu khi thời gian từ lần gửi trước vượt 2 giây; mẫu DHT không hợp lệ sẽ không được gửi.
- Backend subscribe topic và xử lý khi có tin, không gọi xuống ESP để yêu cầu đo.
- Nhiệt độ/độ ẩm/ánh sáng tương ứng sensor ID 1/2/3, lưu cùng thời gian backend theo múi giờ Việt Nam.
- Dữ liệu phải là số hữu hạn; độ ẩm 0–100, ánh sáng không âm. Lỗi lưu DB sẽ rollback cả ba dòng.
- Frontend polling tuần tự, chờ 2 giây sau mỗi chu kỳ; frontend không quyết định việc lưu dữ liệu MQTT.

### Điều khiển thiết bị

```text
Frontend → POST control → lưu History pending
  → MQTT iot/control → ESP xử lý GPIO
  → MQTT iot/status (ACK có command_id)
  → cập nhật History → frontend xác minh kết quả và trạng thái
```

- `History.id` được dùng làm `command_id`; mỗi thiết bị tối đa một lệnh đang pending.
- HTTP `202` chỉ xác nhận tiếp nhận lệnh, chưa xác nhận thiết bị đã thực hiện.
- ACK đúng ID và thiết bị, trạng thái ON/OFF khớp hành động → `success`; khác → `failed`.
- Chưa phản hồi → `pending`; quá hạn mặc định 5 giây → `failed`.
- ACK thiếu ID, trùng hoặc đã quá hạn không được dùng để hoàn tất lệnh.
- Dashboard hiển thị trạng thái ON/OFF đã xác nhận cuối, không phải trạng thái online; chưa có xác nhận là `UNKNOWN`.

## Chạy local

Các lệnh dưới đây dùng PowerShell trên Windows. Với macOS/Linux, thay `.\mvnw.cmd` bằng `./mvnw`.

### 1. Chuẩn bị

- Cài JDK 21, MySQL và Mosquitto.
- Cài Python nếu chạy frontend bằng HTTP server; có thể dùng VS Code Live Server thay thế.
- Cài Arduino IDE, ESP8266 board core và các thư viện firmware nêu trên nếu test phần cứng.
- Node.js 24 dùng cho kiểm thử frontend, không bắt buộc để mở giao diện.

### 2. MySQL và cấu hình backend

Khởi động MySQL, tạo database khớp datasource URL trong `backend/src/main/resources/application.properties`. Kiểm tra các khóa cấu hình trên máy:

| Nhóm | Khóa |
| --- | --- |
| MySQL | `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` |
| JWT | `jwt.secret`, `jwt.expiration` |
| MQTT | `mqtt.enabled`, `mqtt.broker.url`, `mqtt.client.id`, `mqtt.username`, `mqtt.password` |

Backend dùng một file cấu hình; không yêu cầu tách dev/test profile. Hibernate cập nhật schema và `data.sql` seed bằng `INSERT IGNORE`; không thay mật khẩu tài khoản đã tồn tại. Giữ năm bảng `user`, `sensors`, `datasensors`, `devices`, `history`; không có Flyway/Liquibase.

Không đăng mật khẩu DB/MQTT, JWT secret hoặc thông tin Wi-Fi lên tài liệu/log. Những file cấu hình và phần kết nối firmware có dữ liệu nhạy cảm; cần kiểm tra trước khi chia sẻ repo.

### 3. MQTT broker

Chạy Mosquitto với cấu hình broker của bạn:

```powershell
mosquitto -c PATH_TO_BROKER_CONFIG -v
```

Thay `PATH_TO_BROKER_CONFIG` bằng đường dẫn thật. Repo không cung cấp file cấu hình Mosquitto. Listener, xác thực và firewall phải cho phép backend và ESP kết nối; ESP dùng địa chỉ LAN của broker, không dùng `localhost` để trỏ đến máy tính.

### 4. Backend

Từ gốc repo:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Frontend hiện gọi API tại `http://localhost:8080`. Nếu chỉ test REST khi chưa chạy broker:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--mqtt.enabled=false'
```

Chế độ này vẫn cần MySQL; API điều khiển trả `503` vì MQTT không sẵn sàng. Swagger/OpenAPI dùng đường dẫn cấu hình `springdoc.swagger-ui.path` và `springdoc.api-docs.path`.

### 5. Frontend và đăng nhập

Mở terminal khác tại gốc repo:

```powershell
python -m http.server 5500 --directory frontend
```

Mở `http://localhost:5500/index.html`, hoặc dùng Live Server. Không mở bằng `file://`. CORS hiện cho phép các origin HTTP localhost/127.0.0.1.

Đăng nhập bằng tài khoản đã có trong DB. Có thể kiểm tra tên tài khoản bằng câu SQL chỉ đọc:

```sql
SELECT id, username FROM `user`;
```

Mật khẩu BCrypt không thể đọc ngược để lấy mật khẩu đăng nhập. Dùng mật khẩu đã thiết lập; nếu không biết, cần đặt lại qua người quản lý DB. Hệ thống hiện không có API đăng ký/quên mật khẩu.

Khi không có mẫu, dashboard hiển thị `--` và chart rỗng. Mất kết nối API sẽ xóa số/chart cũ và khóa switch khi không xác minh được trạng thái thiết bị.

### 6. ESP8266

1. Mở `sketch_aug25a.ino` bằng Arduino IDE; chọn board và cổng COM phù hợp.
2. Kiểm tra dây nối, thư viện và thông tin Wi-Fi/MQTT trên máy; broker phải cùng địa chỉ với backend.
3. Compile rồi upload firmware; chỉ cắm USB không cập nhật chương trình trên ESP.
4. Theo dõi Serial Monitor để kiểm tra kết nối và payload cảm biến.
5. Mở dashboard kiểm tra số đo; bật/tắt từng đầu ra và đối chiếu GPIO với lịch sử lệnh.

Firmware phải echo `command_id` trong ACK. Firmware cũ không có ID sẽ khiến backend bỏ ACK và lệnh timeout.

## REST API

Ngoại trừ login và tài liệu Swagger/OpenAPI, API cần header `Authorization: Bearer <JWT>`.

| Method | Endpoint | Chức năng |
| --- | --- | --- |
| POST | `/api/auth/login` | Đăng nhập, trả token và username |
| GET | `/api/sensors/latest` | Mẫu đầy đủ mới nhất hoặc `null` |
| GET | `/api/sensors/chart` | Tối đa 15 mẫu đầy đủ, cũ → mới |
| GET | `/api/sensors/history` | Lịch sử cảm biến có tìm kiếm/phân trang |
| GET | `/api/devices/status` | Trạng thái ON/OFF/UNKNOWN đã xác nhận |
| POST | `/api/devices/control` | Gửi lệnh điều khiển, trả `202/pending` |
| GET | `/api/devices/history` | Lịch sử lệnh có bộ lọc/phân trang |

Body điều khiển:

```json
{"device":"led","action":"ON"}
```

`device`: `led`/`fan`/`ac`; `action`: `ON`/`OFF`. Người điều khiển lấy từ JWT, không lấy từ body.

Query lịch sử cảm biến: `page`, `limit`, `search`, `search_type`, `sort`.

- `search_type=all` (mặc định): tìm tên cảm biến, giá trị hoặc thời gian.
- `time`: chỉ tìm thời gian.
- `temperature`, `humidity`, `light`: chỉ tìm giá trị của loại cảm biến tương ứng.
- Dropdown Data Sensor chỉ chọn tiêu chí; bấm Tìm Kiếm hoặc Enter mới áp dụng.

Query lịch sử thiết bị: `page`, `limit`, `device`, `action`, `command_status`, `search`, `sort`.

- `device`: `Đèn LED`/`Quạt`/`Điều hoà`; bỏ trống để lấy tất cả.
- `command_status`: `success`/`failed`/`pending`; không phải ACK ON/OFF.
- `search`: người điều khiển hoặc thời gian; thời gian tìm bằng `DATE_FORMAT + LIKE`.
- Cả hai API: page từ 1, limit 1–100, search tối đa 100 ký tự, sort `asc`/`desc`.
- Thời gian hiển thị `YYYY-MM-DD HH:mm:ss` (+07:00), có thể copy vào ô tìm kiếm. API vẫn trả timestamp ISO có offset.

Response thống nhất đúng ba trường:

```json
{
  "status": 200,
  "message": "Thông báo",
  "data": null
}
```

`status` là mã HTTP, khác `command_status` của lệnh. Kết quả phân trang nằm trong `data` gồm `data`, `total_records`, `total_pages`, `current_page`. Lỗi thường gặp: `400` tham số sai, `401` chưa xác thực, `403` không có quyền, `409` thiết bị đang có pending, `503` MQTT chưa sẵn sàng/lỗi publish.

## MQTT payload

| Topic | Chiều | Payload ví dụ |
| --- | --- | --- |
| `iot/sensor/data` | ESP → backend qua broker | `{"temperature":28.5,"humidity":65,"light":534}` |
| `iot/control` | Backend → ESP qua broker | `{"device":"led","cmd":"ON","command_id":12}` |
| `iot/status` | ESP → backend qua broker | `{"device":"led","status":"ON","command_id":12}` |

Backend subscribe sensor/status với QoS 1, retry kết nối/subscription mỗi 5 giây. Lệnh control publish UTF-8 JSON, QoS 1, không retained. ACK cũng hỗ trợ `success`/`failed`/`pending` và `device_status` ON/OFF tùy chọn; sketch hiện gửi ACK ON/OFF.

## Kiểm thử

Test backend thường, chạy trong `backend/`:

```powershell
.\mvnw.cmd test
```

Các test thường dùng H2 cấu hình tại test. Test cần MySQL/Mosquitto chỉ chạy khi bật cờ tương ứng, không phải khi khởi động ứng dụng.

Test frontend, chạy từ gốc repo:

```powershell
node --test --test-isolation=none frontend/tests/frontend.test.mjs
```

Kiểm thử tích hợp và build, chạy trong `backend/`:

```powershell
.\mvnw.cmd '-Diot.mysql.tests=true' '-Diot.mqtt.tests=true' '-Diot.frontend.tests=true' '-Diot.mqtt.executable=PATH_TO_MOSQUITTO' verify
```

- Thay `PATH_TO_MOSQUITTO` bằng executable thực tế; bỏ tham số nếu Mosquitto đã ở PATH.
- MySQL test cần quyền tạo/xóa schema tạm, không chạy trên bảng DB chính.
- Thêm `'-Diot.frontend.browser=PATH_TO_CHROME_OR_EDGE'` để chạy browser smoke test bằng profile tạm.
- Integration test dùng broker/cổng và firmware giả lập; không thay thế test ESP/mạch thật. Chart CDN được double trong browser smoke test, cần kiểm tra biểu đồ thật trên trình duyệt.

## Giới hạn và lưu ý

- Trạng thái ON/OFF là xác nhận cuối, không có heartbeat hay thời gian ACK; thiết bị có thể đã offline hoặc reboot.
- Giữ ERD năm entity, không có `measurement_id`; bản tin nhận lặp có thể tạo thêm bản ghi. Latest/chart bỏ timestamp thiếu hoặc trùng sensor để tránh ghép sai mẫu.
- Giá trị ánh sáng đang map ADC 0–1023 thành 0–1000, chưa hiệu chuẩn; nhãn Lux trên UI không chứng minh độ rọi vật lý.
- DB cũ có thể cần `backend/sql/normalize-command-status.sql`. Chỉ chạy sau backup, dừng backend và xác nhận schema phù hợp; Hibernate update không tự xóa bảng/cột thừa.
- `docs/`, ngữ cảnh agent và ảnh ERD là tài liệu local đã ignore, không đi kèm bản clone repo.
