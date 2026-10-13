package com.iot.backend.service;

import com.iot.backend.dto.request.DeviceControlRequest;
import com.iot.backend.dto.response.DeviceControlResponse;
import com.iot.backend.dto.response.DeviceStatusResponse;
import com.iot.backend.entity.Device;
import com.iot.backend.entity.History;
import com.iot.backend.repository.DeviceRepository;
import com.iot.backend.repository.HistoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class DeviceService {
    private final HistoryService historyService;
    private final DeviceRepository deviceRepository;
    private final HistoryRepository historyRepository;
    private final ObjectProvider<MqttService> mqttProvider;

    public DeviceService(HistoryService historyService, DeviceRepository deviceRepository,
                         HistoryRepository historyRepository, ObjectProvider<MqttService> mqttProvider) {
        this.historyService = historyService;
        this.deviceRepository = deviceRepository;
        this.historyRepository = historyRepository;
        this.mqttProvider = mqttProvider;
    }

    @Transactional(readOnly = true)
    public List<DeviceStatusResponse> getStatuses() {
        List<Device> devices = deviceRepository.findAll(Sort.by("id"));
        return devices.stream().map(device -> {
            String state = historyRepository.findTopByDeviceIdAndStatusReceivedIsNotNullAndStatusInOrderByCreatedAtDescIdDesc(
                    device.getId(), List.of("success", "failed"))
                    .map(History::getStatusReceived).filter(value -> List.of("ON", "OFF").contains(value))
                    .orElse("UNKNOWN");
            return new DeviceStatusResponse(device.getCode(), state, null);
        }).toList();
    }

    public DeviceControlResponse control(DeviceControlRequest request, String username) {
        validateControlRequest(request, username);
        MqttService mqtt = requireMqttConnection();

        // Bước 1: lưu và commit pending để ACK có thể tìm đúng command_id.
        DeviceControlResponse receipt = historyService.createPendingCommand(request.device(), request.action(), username);

        // Bước 2: gửi MQTT ngoài transaction; kết quả cuối do ACK/timeout cập nhật.
        try {
            mqtt.publishDeviceControl(receipt.device(), receipt.action(), receipt.commandId());
        } catch (MqttException | RuntimeException exception) {
            historyService.markPublishFailed(receipt); // Giữ success nếu ACK đã về trước lỗi publish.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không gửi được lệnh MQTT", exception);
        }
        return receipt;
    }

    private void validateControlRequest(DeviceControlRequest request, String username) {
        if (request == null || request.device() == null || request.action() == null
                || !List.of("led", "fan", "ac").contains(request.device())
                || !List.of("ON", "OFF").contains(request.action())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lệnh điều khiển không hợp lệ");
        }
        if (username == null || username.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private MqttService requireMqttConnection() {
        MqttService mqtt = mqttProvider.getIfAvailable();
        if (mqtt == null || !mqtt.isReadyForControl()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT chưa sẵn sàng");
        }
        return mqtt;
    }
}
