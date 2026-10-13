# Checklist ESP thật — giai đoạn 8

- Chưa có kết quả phần cứng thật. Test tự động dùng schema/broker/cổng riêng và firmware giả lập; không nạp ESP.
- README chạy local: [backend/README.md](../backend/README.md). Không đưa secret/tài khoản kết nối vào ảnh hoặc log gửi lên git/chat.

## 1. Chuẩn bị trước khi cắm/test

- TODO: xác nhận board ESP8266 cụ thể, cổng COM, ESP8266 core và phiên bản DHT/PubSubClient/ArduinoJson trong Arduino IDE.
- Firmware hiện có ở gốc `sketch_aug25a.ino`; mở/compile bằng Arduino IDE, chọn đúng board/cổng rồi Upload. Không dùng bản nạp cũ chưa echo command_id.
- Arduino IDE có thể yêu cầu sketch nằm trong thư mục trùng tên `sketch_aug25a`; nếu phải tạo bản local thì không vô tình commit bản chứa credentials.
- Kết nối trong sketch phải trỏ cùng broker mà backend dùng. Tự kiểm tra thông tin Wi-Fi/MQTT trên máy, không sao chép giá trị vào tài liệu.
- PC và ESP phải truy cập được broker qua mạng. Địa chỉ localhost chỉ dùng được trên máy chạy broker, không phải địa chỉ của PC nhìn từ ESP.
- Dùng cấu hình Mosquitto hiện có với listener/auth phù hợp LAN; kiểm tra firewall cho kết nối ESP. Không tự mở broker ra Internet hoặc bỏ xác thực để chữa lỗi.
- MySQL/database đã chạy; cần chuẩn hóa history.status cũ thì backup/dừng backend/xác nhận trước khi chạy SQL thủ công. Không chạy migration 004 đã bỏ.
- GPIO theo sketch: DHT11 D4; ánh sáng A0; LED D1; quạt D2; điều hòa D5. TODO: đối chiếu board/mạch thực tế, cực tính relay và nguồn; sketch coi HIGH=ON, LOW=OFF.
- Chỉ thử với đầu ra/LED mô phỏng hoặc mạch điều khiển phù hợp đã được kiểm tra; không nối tải quạt/điều hòa trực tiếp vào GPIO, không thử điện lưới khi chưa có người đủ chuyên môn xác nhận mạch.

## 2. Chạy hệ thống

- Khởi động MySQL và Mosquitto bằng cấu hình đang dùng.
- Trong backend: `.\mvnw.cmd spring-boot:run`; không truyền mqtt.enabled=false khi test ESP. Chờ backend kết nối và subscribe MQTT.
- Từ gốc: `python -m http.server 5500 --directory frontend`; mở http://localhost:5500/index.html, đăng nhập bằng tài khoản DB thật.
- Frontend chỉ dùng backend thật, đã bỏ chế độ demo. Token xem thử cũ bị loại; đăng nhập lại nếu có 401.
- Cắm/nạp ESP; Serial Monitor 115200 baud. Chu kỳ gửi hiện tại trong sketch đã đổi thành khoảng 2 giây (xác nhận 09/10); phải nạp bản này lên ESP mới có hiệu lực.
- Cắm USB chỉ cấp nguồn/Serial; dữ liệu hệ thống đi qua Wi-Fi/MQTT, không qua USB.

## 3. Kiểm tra cảm biến

- [ ] Dashboard có nhãn Dữ liệu thật và timestamp mới; khi DB chưa có mẫu phải là --/chart rỗng, không còn số mẫu.
- [ ] Thay đổi điều kiện đo, đợi chu kỳ gửi khoảng 2 giây + chu kỳ poll 2 giây; card và chart thay đổi theo mẫu ESP, không tự nhảy ngẫu nhiên.
- [ ] Data Sensor có đủ Nhiệt Độ/Độ Ẩm/Ánh Sáng tại cùng thời gian; lọc từng loại/giá trị, tìm ngày/giờ, sort, page size và chuyển trang.
- [ ] Giá trị 0 thật vẫn hiển thị; tìm không có kết quả và page vượt cuối không trả lại trang đầu.
- [ ] Chart không trộn điểm mẫu với điểm thật, không thêm lặp cùng timestamp; kiểm tra Chart.js CDN tải được trong Network.
- Ánh sáng hiện là ADC map 0–1000, chưa hiệu chuẩn lux; đừng đối chiếu nhãn Lux trên UI như đơn vị vật lý đã xác nhận.

## 4. Kiểm tra điều khiển/ACK

- [ ] Bật LED: POST control 202, receipt có command_id và command_status=pending; công tắc bị khóa/hiển thị chờ ACK, không báo thành công từ 202.
- [ ] Serial `Nhan lenh:` có device/cmd/command_id; ACK iot/status echo cùng ID, status ON/OFF.
- [ ] History cùng command_id chuyển success, status_received=ON; dashboard trạng thái xác nhận chuyển bật. Tắt LED làm lại với OFF.
- [ ] Thử fan/ac chỉ khi mạch thử đã được xác nhận an toàn; kiểm tra hành vi đầu ra và history, không chỉ nhìn ACK JSON.
- [ ] History có Hành động ON/OFF và một Trạng thái success/failed/pending (không cột ACK riêng), username thực, giờ tạo lệnh +07:00. ACK ON/OFF khớp action thì success, khác thì failed; dropdown Trạng thái lọc đúng ba kết quả, copy giờ/tìm username hoạt động.
- [ ] Rút ESP nhưng giữ broker/backend chạy, gửi lệnh: pending rồi failed sau khoảng 5–7 giây; không tự bật/tắt trạng thái đã xác nhận trước đó. Khi chờ ACK, History hiển thị pending.
- [ ] Cắm lại ESP, đợi MQTT reconnect, lệnh mới thành công. UNKNOWN/ON/OFF chỉ là xác nhận cuối, không bảo đảm thiết bị online hoặc đồng bộ ngay sau reboot.

## 5. Kiểm tra lỗi và phiên đăng nhập

- [ ] Ngắt broker khi thử: control báo MQTT chưa sẵn sàng/503, không chuyển sang mô phỏng thành công; khôi phục broker và chờ retry rồi thử lại.
- [ ] Ngắt backend: card -- và chart rỗng kèm cảnh báo kết nối, switch bị khóa; bảng báo lỗi, không hiển thị dữ liệu giả.
- [ ] Đăng nhập sai trả 401, không bật demo. Đăng xuất về index; truy cập dashboard khi không có JWT phải về login.
- [ ] Thử màn hình nhỏ/mobile menu, tìm kiếm Enter, dropdown/page buttons, nút logout; xác nhận không có lỗi JavaScript trong Console.

## 6. Nếu chưa chạy được

| Hiện tượng | Kiểm tra trước |
|---|---|
| Không có Gui du lieu / không có sensor row | Wi-Fi/MQTT trên Serial, DHT NaN/mạch chân, broker/topic, backend subscription; tránh client_id trùng |
| POST 503 | MQTT backend chưa subscribe/kết nối, broker/auth/network; không phải lỗi JWT |
| POST 409 | Thiết bị đang có pending; xem history, đợi timeout rồi thử lại |
| Tất cả lệnh failed nhưng GPIO đổi | Firmware echo command_id chưa nạp, ACK topic/auth/publish lỗi, ACK về sau deadline |
| success nhưng tải không đổi | ACK chỉ xác nhận firmware đã xử lý GPIO; kiểm tra mạch/nguồn/cực tính, không thử điện lưới tùy tiện |
| 401 hoặc vào lại login | Token cũ/hết hạn/demo; đăng nhập thật, kiểm tra Network status, không chia sẻ token |
| Chart trống nhưng card có số thật | CDN Chart.js chưa tải hoặc chưa có snapshot đủ ba sensor; xem Console/Network |

- Ghi ngày test, board/cổng/thư viện (không secret), case pass/fail và lỗi đã che token/password. Sau khi toàn bộ checklist qua mới đánh dấu GĐ8 hoàn tất phần cứng/bàn giao.
