package com.iot.backend.controller;

import com.iot.backend.dto.response.ApiResponse;
import com.iot.backend.dto.response.PageResponse;
import com.iot.backend.dto.response.SensorChartResponse;
import com.iot.backend.dto.response.SensorHistoryResponse;
import com.iot.backend.dto.response.SensorLatestResponse;
import com.iot.backend.service.SensorService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sensors")
public class SensorController {

    private final SensorService sensorService;

    public SensorController(SensorService sensorService) {
        this.sensorService = sensorService;
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<SensorLatestResponse>> getLatestData() {
        SensorLatestResponse data = sensorService.getLatestData();
        return ResponseEntity.ok(ApiResponse.success(
                data == null ? "Chưa có dữ liệu cảm biến" : "Lấy dữ liệu cảm biến mới nhất thành công", data));
    }

    @GetMapping("/chart")
    public ResponseEntity<ApiResponse<List<SensorChartResponse>>> getChartData() {
        List<SensorChartResponse> data = sensorService.getChartData();
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResponse<SensorHistoryResponse>>> getHistory(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "all", name = "search_type")
            @Pattern(regexp = "all|time|temperature|humidity|light") String searchType,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String sort) {
        PageResponse<SensorHistoryResponse> data = sensorService.getHistory(page, limit, search, searchType, sort);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
