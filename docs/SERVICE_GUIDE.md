# Service — cách đọc và trình bày

- Hệ thống có 5 service nghiệp vụ; không thay API, entity/bảng, MQTT payload hoặc giao diện.
- Repository chứa query JPQL/derived method; service xử lý nghiệp vụ, transaction và chuyển entity sang DTO.

## Trách nhiệm

| Lớp | Công việc chính |
|---|---|
| AuthService | Đăng nhập, xác thực tài khoản và tạo JWT |
| SensorService | saveMeasurement, getLatestData, getChartData, getHistory |
| DeviceService | control (gửi lệnh), getStatuses (trạng thái xác nhận cuối) |
| HistoryService | createPendingCommand, handleAcknowledgement, markPublishFailed, getHistory, xử lý lệnh hết hạn |
| MqttService | Kết nối/subscribe, decode/validate payload, gửi lệnh, chuyển dữ liệu/ACK sang service nghiệp vụ |

- scheduler/DeviceCommandTimeoutTask là tác vụ chạy định kỳ, không phải service nghiệp vụ thứ sáu.
- Không cần thêm DataInitService: dự án đã seed bằng data.sql; không đưa credentials vào tài liệu.

## Luồng cảm biến

1. ESP gửi JSON lên iot/sensor/data.
2. MqttService kiểm tra UTF-8 và SensorMeasurementPayload kiểm tra dữ liệu số hợp lệ.
3. MqttService gọi SensorService.saveMeasurement qua bean Spring.
4. Một transaction lưu 3 sensor cùng created_at: thành công cả ba hoặc rollback cả ba.
5. Frontend gọi API latest/chart/history; SensorService query DB rồi trả DTO, không bù số giả.

## Luồng điều khiển

1. DeviceController lấy username từ Authentication/JWT, không lấy user do frontend gửi.
2. DeviceService kiểm tra yêu cầu và MQTT đã sẵn sàng.
3. HistoryService.createPendingCommand khóa Device, kiểm tra chưa có pending còn hạn, lưu/commit History.
4. History.id làm command_id; DeviceService gửi MQTT sau khi transaction đã kết thúc.
5. ESP phản hồi iot/status; MqttService gọi HistoryService.handleAcknowledgement.
6. ACK đúng ID/thiết bị và lệnh còn pending: ON/OFF khớp action → success, khác → failed.
7. Chưa phản hồi vẫn pending; scheduler quét mỗi giây, quá hạn mặc định 5 giây → failed. Publish lỗi cũng failed, nhưng không ghi đè success đã nhận sớm.

## Câu hỏi thường gặp khi trình bày

- Vì sao không success ngay khi publish? Publish chỉ là gửi tới broker; phải đợi ESP xác nhận.
- Vì sao có command_id? Để ACK không cập nhật nhầm lệnh cũ hoặc lệnh của thiết bị khác.
- Vì sao vẫn lưu status_received? Đây là ON/OFF thực tế để dashboard đọc; status là kết quả success/failed/pending. UI History chỉ hiện action/status.
- Vì sao transaction ở HistoryService riêng với DeviceService? Pending phải commit trước khi gửi; không giữ khóa DB trong lúc chờ mạng. Gọi bean khác để @Transactional thực sự có hiệu lực.
- Vì sao gộp SensorMeasurementService vào SensorService được? MqttService vẫn gọi bean SensorService bên ngoài nên transaction lưu cảm biến không bị mất.
- Vì sao scheduler riêng? Timeout không bị chậm theo quá trình kết nối lại MQTT.
- Không chuyển sang Specification, WebSocket hoặc thêm bảng/cột; refactor này chỉ đổi cách tổ chức code.

## Các lớp đã gộp/chuyển

- SensorMeasurementService → SensorService.
- DeviceCommandTransactions + DeviceHistoryService → HistoryService.
- Truy vấn trạng thái xác nhận từ DeviceCommandTransactions → DeviceService.
- service/DeviceCommandTimeoutTask → scheduler/DeviceCommandTimeoutTask.

- Các service cũ được bỏ sau khi chuyển logic và sửa tất cả nơi gọi/test. Không có lớp wrapper giữ lại tên cũ.
