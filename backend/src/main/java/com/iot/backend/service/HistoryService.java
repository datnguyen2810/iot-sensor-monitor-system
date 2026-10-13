package com.iot.backend.service;

import com.iot.backend.dto.mqtt.DeviceAcknowledgement;
import com.iot.backend.dto.response.DeviceControlResponse;
import com.iot.backend.dto.response.DeviceHistoryResponse;
import com.iot.backend.dto.response.PageResponse;
import com.iot.backend.entity.History;
import com.iot.backend.repository.DeviceRepository;
import com.iot.backend.repository.HistoryRepository;
import com.iot.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Lưu lịch sử lệnh, xử lý ACK và tra cứu; mỗi thao tác DB là một transaction ngắn. */
@Service
public class HistoryService {
    private static final Map<String, String> DEVICES = Map.of(
            "Đèn LED", "led", "Quạt", "fan", "Điều hoà", "ac");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final DeviceRepository deviceRepository;
    private final HistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final Duration timeout;

    public HistoryService(DeviceRepository deviceRepository, HistoryRepository historyRepository, UserRepository userRepository,
                          @Value("${devices.command.timeout-ms:5000}") long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Command timeout must be positive");
        this.deviceRepository = deviceRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional
    public DeviceControlResponse createPendingCommand(String code, String action, String username) {
        var device = deviceRepository.findByCodeForUpdate(code).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thiết bị"));

        // Khóa thiết bị: mỗi thiết bị chỉ có một lệnh pending chưa hết hạn.
        historyRepository.failExpiredPending(device.getId(), now().minus(timeout));
        if (historyRepository.existsByDeviceIdAndStatus(device.getId(), "pending")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Thiết bị đang có lệnh chờ phản hồi");
        }

        var user = userRepository.findByUsername(username).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không còn tồn tại"));

        var history = historyRepository.saveAndFlush(
                        History.builder()
                                .device(device)
                                .user(user)
                                .action(action)
                                .status("pending")
                                .createdAt(now())
                                .build());

        return new DeviceControlResponse(history.getId(), code, action, "pending");
    }

    @Transactional
    public void markPublishFailed(DeviceControlResponse receipt) {
        deviceRepository.findByCodeForUpdate(receipt.device())
                .ifPresent(device -> historyRepository.failPending(receipt.commandId(), device.getId()));
    }

    @Transactional
    public void handleAcknowledgement(DeviceAcknowledgement ack) {
        var device = deviceRepository.findByCodeForUpdate(ack.device());
        if (device.isEmpty()) return;
        var command = historyRepository.findByIdAndDeviceId(ack.commandId(), device.get().getId());
        if (command.isEmpty() || !"pending".equals(command.get().getStatus())) return;
        History history = command.get();
        if (!history.getCreatedAt().plus(timeout).isAfter(now())) {
            history.setStatus("failed");
            return; // ACK trễ không được đổi failed trở lại success.
        }
        if ("pending".equals(ack.status())) return;
        boolean stateReply = List.of("ON", "OFF").contains(ack.status());
        String deviceState = stateReply ? ack.status() : ack.deviceStatus();
        String commandStatus = ack.status();
        if (stateReply || "success".equals(commandStatus)) {
            if (deviceState.isEmpty()) deviceState = history.getAction();
            commandStatus = history.getAction().equals(deviceState) ? "success" : "failed";
        }
        history.setStatus(commandStatus);
        if (!deviceState.isEmpty()) history.setStatusReceived(deviceState);
        // JPA lưu thay đổi khi transaction kết thúc, không thêm cột thiết bị.
    }

    @Transactional(readOnly = true)
    public List<String> findExpiredDevices() {
        return historyRepository.findExpiredPendingDeviceCodes(now().minus(timeout));
    }

    @Transactional
    public void expirePendingCommands(String code) {
        deviceRepository.findByCodeForUpdate(code)
                .ifPresent(device -> historyRepository.failExpiredPending(device.getId(), now().minus(timeout)));
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceHistoryResponse> getHistory(int page, int limit, String device,
            String action, String commandStatus, String search, String sort) {
        String name = emptyIfNull(device);
        String sent = emptyIfNull(action);
        String outcome = emptyIfNull(commandStatus);
        if (page < 1 || limit < 1 || limit > 100 || (!name.isEmpty() && !DEVICES.containsKey(name))
                || !List.of("", "ON", "OFF").contains(sent)
                || !List.of("", "success", "failed", "pending").contains(outcome)
                || !List.of("asc", "desc").contains(emptyIfNull(sort))
                || (search != null && search.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số lịch sử thiết bị không hợp lệ");
        }
        String keyword = emptyIfNull(search).trim();
        String escaped = keyword.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");

        var pageable = PageRequest.of(page - 1, limit,
                Sort.by("asc".equals(sort) ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt", "id"));

        var result = historyRepository.searchHistory(DEVICES.get(name), nullIfEmpty(sent),
                nullIfEmpty(outcome), keyword.isEmpty() ? null : "%" + escaped + "%", pageable);

        var rows = result.getContent().stream().map(this::toHistoryResponse).toList();
        return PageResponse.<DeviceHistoryResponse>builder()
                            .data(rows)
                            .totalRecords(result.getTotalElements())
                            .totalPages(Math.max(1, result.getTotalPages()))
                            .currentPage(result.getTotalElements() == 0 ? 1 : page)
                            .build();
    }

    private DeviceHistoryResponse toHistoryResponse(History history) {
        String operator = history.getUser() == null ? "Hệ thống (Auto)" : history.getUser().getUsername();
        String time = history.getCreatedAt().atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new DeviceHistoryResponse(history.getId(), history.getId(), history.getDevice().getName(),
                history.getAction(), history.getStatusReceived(), history.getStatus(), operator, time);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
