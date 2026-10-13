package com.iot.backend.service;

import com.iot.backend.dto.response.*;
import com.iot.backend.dto.mqtt.SensorMeasurementPayload;
import com.iot.backend.repository.SensorRepository;
import com.iot.backend.entity.DataSensor;
import com.iot.backend.repository.DataSensorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class SensorService {
    private static final ZoneId SENSOR_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Map<String, Integer> SENSOR_TYPES = Map.of(
            "temperature", 1, "humidity", 2, "light", 3);
    private static final DateTimeFormatter SEARCH_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DataSensorRepository dataSensorRepository;
    private final SensorRepository sensorRepository;

    public SensorService(DataSensorRepository dataSensorRepository, SensorRepository sensorRepository) {
        this.dataSensorRepository = dataSensorRepository;
        this.sensorRepository = sensorRepository;
    }

    /** MQTT gọi qua bean SensorService: ba giá trị cùng thời gian, lưu đủ hoặc rollback cả ba. */
    @Transactional
    public void saveMeasurement(SensorMeasurementPayload payload) {
        LocalDateTime time = LocalDateTime.now(SENSOR_ZONE).truncatedTo(ChronoUnit.MICROS);
        float[] values = {payload.temperature(), payload.humidity(), payload.light()};
        var batch = new ArrayList<DataSensor>(3);
        for (int sensorId : List.of(1, 2, 3)) {
            batch.add(DataSensor.builder()
                                .sensor(sensorRepository.findById(sensorId).orElseThrow(
                                        () -> new IllegalStateException("Missing seeded sensor")))
                                .value(values[sensorId - 1])
                                .createdAt(time)
                                .build());
        }
        dataSensorRepository.saveAllAndFlush(batch);
    }

    @Transactional(readOnly = true)
    public SensorLatestResponse getLatestData() {
        List<SensorChartResponse> snapshots = snapshots(1);
        if (snapshots.isEmpty()) return null;
        SensorChartResponse latest = snapshots.getFirst();
        return SensorLatestResponse.builder()
                                    .temperature(latest.getTemperature())
                                    .humidity(latest.getHumidity())
                                    .light(latest.getLight())
                                    .timestamp(latest.getTimestamp())
                                    .build();
    }

    @Transactional(readOnly = true)
    public List<SensorChartResponse> getChartData() {
        return snapshots(15);
    }

    private List<SensorChartResponse> snapshots(int limit) {
        List<LocalDateTime> times = dataSensorRepository.findCompleteMeasurementTimes(PageRequest.of(0, limit));
        if (times.isEmpty()) return List.of();
        Map<LocalDateTime, SensorChartResponse> points = new TreeMap<>();
        for (DataSensor record : dataSensorRepository.findByCreatedAtInOrderByCreatedAtAsc(times)) {
            SensorChartResponse point = points.computeIfAbsent(record.getCreatedAt(), time ->
                    SensorChartResponse.builder().time(time.format(SEARCH_TIME))
                            .timestamp(formatTimestamp(time)).build());
            switch (record.getSensor().getId()) {
                case 1 -> point.setTemperature(record.getValue());
                case 2 -> point.setHumidity(record.getValue());
                case 3 -> point.setLight(record.getValue());
                default -> { }
            }
        }
        // Không bù số 0 khi mẫu bị thiếu, kể cả khi DB thay đổi giữa hai query.
        return points.values().stream().filter(point -> point.getTemperature() != null
                && point.getHumidity() != null && point.getLight() != null).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<SensorHistoryResponse> getHistory(int page, int limit, String search, String searchType, String sort) {
        String type = searchType == null ? "all" : searchType;
        String keyword = search == null ? "" : search.trim();
        // Giữ validation khi service được gọi ngoài controller.
        if (page < 1 || limit < 1 || limit > 100
                || (!List.of("all", "time").contains(type) && !SENSOR_TYPES.containsKey(type))
                || !List.of("asc", "desc").contains(sort == null ? "" : sort)
                || (search != null && search.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số tìm kiếm cảm biến không hợp lệ");
        }

        var pageable = PageRequest.of(page - 1, limit,
                Sort.by("asc".equals(sort) ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt", "id"));

        String pattern = keyword.isEmpty() ? null : "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";

        // Một tiêu chí API quyết định cả sensor cần lọc và trường cần tìm trong query.
        String mode = switch (type) {
            case "all" -> "";
            case "time" -> "time";
            default -> "value";
        };

        Page<DataSensor> result = dataSensorRepository.searchHistory(SENSOR_TYPES.get(type), pattern, mode, pageable);

        List<SensorHistoryResponse> rows = result.getContent().stream().map(this::toHistoryResponse).toList();

        return PageResponse.<SensorHistoryResponse>builder()
                            .data(rows)
                            .totalRecords(result.getTotalElements())
                            .totalPages(Math.max(1, result.getTotalPages()))
                            .currentPage(result.getTotalElements() == 0 ? 1 : page)
                            .build();
    }

    private SensorHistoryResponse toHistoryResponse(DataSensor record) {
        return SensorHistoryResponse.builder()
                .id(record.getId())
                .sensorName(record.getSensor().getName())
                .value(record.getValue())
                .unit(record.getSensor().getUnit())
                .createdAt(formatTimestamp(record.getCreatedAt()))
                .build();
    }

    private static String formatTimestamp(LocalDateTime time) {
        return time.atZone(SENSOR_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
