package com.iot.backend.controller;

import com.iot.backend.dto.request.DeviceControlRequest;
import com.iot.backend.dto.response.ApiResponse;
import com.iot.backend.dto.response.DeviceControlResponse;
import com.iot.backend.dto.response.DeviceStatusResponse;
import com.iot.backend.dto.response.DeviceHistoryResponse;
import com.iot.backend.dto.response.PageResponse;
import com.iot.backend.service.HistoryService;
import com.iot.backend.service.DeviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceService deviceService;
    private final HistoryService historyService;
    public DeviceController(DeviceService deviceService, HistoryService historyService) {
        this.deviceService = deviceService;
        this.historyService = historyService;
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResponse<DeviceHistoryResponse>>> history(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|Đèn LED|Quạt|Điều hoà") String device,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|ON|OFF") String action,
            @RequestParam(name = "command_status", defaultValue = "")
            @Pattern(regexp = "|success|failed|pending") String commandStatus,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String sort) {
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử thiết bị thành công",
                historyService.getHistory(page, limit, device, action, commandStatus, search, sort)));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<List<DeviceStatusResponse>>> status() {
        return ResponseEntity.ok(ApiResponse.success("Lấy trạng thái thiết bị thành công", deviceService.getStatuses()));
    }

    @PostMapping("/control")
    public ResponseEntity<ApiResponse<DeviceControlResponse>> control(
            @Valid @RequestBody DeviceControlRequest request, Authentication authentication) {
        return ResponseEntity.status(202).body(ApiResponse.success(202, "Đã tiếp nhận lệnh, đang chờ phản hồi",
                deviceService.control(request, authentication.getName())));
    }
}
