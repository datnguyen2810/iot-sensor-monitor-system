package com.iot.backend;

import com.iot.backend.entity.DataSensor;
import com.iot.backend.dto.mqtt.SensorMeasurementPayload;
import com.iot.backend.service.SensorService;
import com.iot.backend.service.MqttService;
import com.iot.backend.service.HistoryService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.iot.backend.repository.DataSensorRepository;
import com.iot.backend.repository.SensorRepository;
import com.iot.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:iot_sensor_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false", "spring.jpa.defer-datasource-initialization=true",
        "spring.sql.init.mode=always", "mqtt.enabled=false", "devices.command.timeout-scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
public class SensorApiTests {
    @Autowired SensorService service;
    @MockitoSpyBean DataSensorRepository records;
    @Autowired SensorService ingestion;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SensorRepository sensors;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 7, 10, 0);

    @BeforeEach
    void prepare() {
        records.deleteAll();
        records.flush();
        if (environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:")) {
            // Chỉ mô phỏng function trong H2; cùng test còn được chạy trên MySQL thật.
            jdbc.execute("CREATE ALIAS IF NOT EXISTS date_format FOR 'com.iot.backend.SensorApiTests.mysqlDateFormat'");
        }
    }

    public static String mysqlDateFormat(Timestamp timestamp, String pattern) {
        if (!"%Y-%m-%d %H:%i:%s".equals(pattern)) throw new IllegalArgumentException("Unsupported test format");
        return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    void mqttPayloadCreatesExactlyThreeRowsReadableByLatestAndChart() {
        var mqtt = new MqttService(mock(IMqttClient.class), new MqttConnectOptions(), mapper, ingestion,
                mock(HistoryService.class), mock(ScheduledExecutorService.class));
        var message = new MqttMessage("{\"temperature\":28.5,\"humidity\":65,\"light\":0}"
                .getBytes(StandardCharsets.UTF_8));
        mqtt.messageArrived("iot/sensor/data", message);
        assertThat(records.count()).isEqualTo(3);
        assertThat(records.findAll()).extracting(DataSensor::getCreatedAt).containsOnly(records.findAll().getFirst().getCreatedAt());
        assertThat(service.getLatestData().getTemperature()).isEqualTo(28.5f);
        assertThat(service.getLatestData().getLight()).isZero();
        assertThat(service.getChartData()).hasSize(1);
        // No persistent sample ID in the original ERD: a repeated delivery remains a new batch.
        mqtt.messageArrived("iot/sensor/data", message);
        assertThat(records.count()).isEqualTo(6);
    }

    @Test
    void twoBatchesWithSameTimestampAreSkippedRatherThanMixed() {
        ingestion.saveMeasurement(new SensorMeasurementPayload(1, 2, 3));
        ingestion.saveMeasurement(new SensorMeasurementPayload(4, 5, 6));
        jdbc.update("UPDATE datasensors SET created_at = ?", Timestamp.valueOf(START));
        assertThat(service.getChartData()).isEmpty();
        assertThat(service.getLatestData()).isNull();
        assertThat(records.count()).isEqualTo(6);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void thirdInsertFailureRollsBackBothPreviousRowsThenAllowsRetry() {
        // Write two real rows, then violate value NOT NULL on the third row in the same transaction.
        doAnswer(invocation -> {
            List<DataSensor> batch = invocation.getArgument(0);
            records.saveAll(batch.subList(0, 2));
            records.flush();
            assertThat(records.count()).isEqualTo(2);
            batch.get(2).setValue(null);
            records.saveAndFlush(batch.get(2));
            return batch;
        }).when(records).saveAllAndFlush(any());
        try {
            assertThatThrownBy(() -> ingestion.saveMeasurement(new SensorMeasurementPayload(1, 2, 3)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            reset(records);
        }
        assertThat(records.count()).isZero();
        try {
            ingestion.saveMeasurement(new SensorMeasurementPayload(1, 2, 3));
            assertThat(records.count()).isEqualTo(3);
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                records.deleteAll();
                records.flush();
            });
        }
    }

    private void sample(LocalDateTime time, float temp, float humidity, float light) {
        record(time, 1, temp); record(time, 2, humidity); record(time, 3, light);
        records.flush();
    }

    private void record(LocalDateTime time, int sensor, float value) {
        records.save(DataSensor.builder().sensor(sensors.findById(sensor).orElseThrow())
                .createdAt(time).value(value).build());
    }

    @Test
    void emptyDatabaseReturnsNullAndEmptyCollections() throws Exception {
        assertThat(service.getLatestData()).isNull();
        assertThat(service.getChartData()).isEmpty();
        var page = service.getHistory(5, 10, "", "all", "desc");
        assertThat(page.getData()).isEmpty();
        assertThat(page.getTotalRecords()).isZero();
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getCurrentPage()).isEqualTo(1);
        mvc.perform(get("/api/sensors/latest").header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Chưa có dữ liệu cảm biến"));
    }

    @Test
    void snapshotsNeverMixIncompleteOrAmbiguousMeasurementsAndKeepRealZero() {
        sample(START, 0, 0, 0);
        record(START.plusMinutes(1), 1, 99);
        sample(START.plusMinutes(2), 20, 30, 40);
        record(START.plusMinutes(2), 1, 88); // Cùng timestamp có hai nhiệt độ: không đoán.
        records.flush();
        var latest = service.getLatestData();
        assertThat(latest.getTemperature()).isZero();
        assertThat(latest.getHumidity()).isZero();
        assertThat(latest.getLight()).isZero();
        assertThat(latest.getTimestamp()).isEqualTo("2026-10-07T10:00:00+07:00");
        assertThat(service.getChartData()).hasSize(1);
        assertThat(service.getChartData().getFirst().getTimestamp()).isEqualTo(latest.getTimestamp());
    }

    @Test
    void chartContainsLatestFifteenCompleteMeasurementsInAscendingOrder() {
        for (int i = 0; i < 17; i++) sample(START.plusMinutes(i), i, 50, 650);
        var chart = service.getChartData();
        assertThat(chart).hasSize(15);
        assertThat(chart.getFirst().getTemperature()).isEqualTo(2f);
        assertThat(chart.getLast().getTemperature()).isEqualTo(16f);
        assertThat(chart.getLast().getTimestamp()).isEqualTo(service.getLatestData().getTimestamp());
        assertThat(chart.getFirst().getTime()).isEqualTo("10:02:00");
    }

    @Test
    void historySupportsExactSensorValueTimeAndAllFieldsSearch() {
        sample(START, 30.5f, 83, 850);
        sample(START.plusDays(1), 31.2f, 80, 754);
        assertThat(service.getHistory(1, 10, "30.5", "temperature", "desc").getTotalRecords()).isEqualTo(1);
        assertThat(service.getHistory(1, 10, "", "humidity", "desc").getTotalRecords()).isEqualTo(2);
        assertThat(service.getHistory(1, 10, "", "light", "desc").getTotalRecords()).isEqualTo(2);
        assertThat(service.getHistory(1, 10, "2026-10-07", "time", "desc").getTotalRecords()).isEqualTo(3);
        assertThat(service.getHistory(1, 10, "10:00", "time", "desc").getTotalRecords()).isEqualTo(6);
        assertThat(service.getHistory(1, 10, "nhiệt", "all", "desc").getTotalRecords()).isEqualTo(2);
        assertThat(service.getHistory(1, 10, "850", "all", "desc").getTotalRecords()).isEqualTo(1);
        assertThat(service.getHistory(1, 10, "%", "all", "desc").getData()).isEmpty();
        assertThat(service.getHistory(1, 10, "_", "time", "desc").getData()).isEmpty();
    }

    @Test
    void paginationIsStableAndOutOfRangeNeverRepeatsFirstPage() {
        sample(START, 30, 60, 650);
        var first = service.getHistory(1, 2, "", "all", "asc");
        var second = service.getHistory(2, 2, "", "all", "asc");
        assertThat(first.getTotalRecords()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getData()).hasSize(2);
        assertThat(second.getData()).hasSize(1);
        List<Integer> ids = records.findAll().stream().map(DataSensor::getId).sorted().toList();
        assertThat(first.getData().getFirst().getId()).isEqualTo(ids.getFirst());
        assertThat(service.getHistory(1, 2, "", "all", "desc").getData().getFirst().getId()).isEqualTo(ids.getLast());
        var beyond = service.getHistory(10, 2, "", "all", "asc");
        assertThat(beyond.getCurrentPage()).isEqualTo(10);
        assertThat(beyond.getData()).isEmpty();
    }

    @Test
    void filteredPaginationCountMatchesDataQueryForEachSearchMode() {
        sample(START, 30.5f, 50, 650);
        sample(START.plusMinutes(1), 30.5f, 60, 750);
        sample(START.plusDays(1), 40, 70, 850);
        for (String type : List.of("all", "temperature")) {
            var page = service.getHistory(2, 1, "30.5", type, "asc");
            assertThat(page.getTotalRecords()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getData()).hasSize(1);
            assertThat(page.getData().getFirst().getValue()).isEqualTo(30.5f);
        }
        for (String type : List.of("all", "time")) {
            var page = service.getHistory(2, 3, "2026-10-07", type, "asc");
            assertThat(page.getTotalRecords()).isEqualTo(6);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getData()).hasSize(3);
            assertThat(page.getData().getFirst().getCreatedAt()).isEqualTo("2026-10-07T10:01:00+07:00");
        }
        assertThat(service.getHistory(1, 10, "nhiệt", "temperature", "asc").getTotalRecords()).isZero();
        assertThat(service.getHistory(1, 10, "nhiệt", "time", "asc").getTotalRecords()).isZero();
    }

    @Test
    void dateLikeSearchExcludesOtherDatesAndInvalidDateDoesNotMatch() {
        sample(START.toLocalDate().atStartOfDay(), 1, 2, 3);
        sample(START.toLocalDate().atTime(23, 59, 59), 4, 5, 6);
        sample(START.toLocalDate().plusDays(1).atStartOfDay(), 7, 8, 9);
        assertThat(service.getHistory(1, 10, "2026-10-07", "time", "asc").getTotalRecords()).isEqualTo(6);
        assertThat(service.getHistory(1, 10, "2026-02-30", "time", "asc").getTotalRecords()).isZero();
        assertThat(service.getHistory(1, 10, "  ", "time", "asc").getTotalRecords()).isEqualTo(9);
    }

    @ParameterizedTest
    @CsvSource({"time,2026-10-07,3", "time,2026-10,6", "time,10-07,3", "time,10:00,6",
            "time,2026-10-07 10:00:00,3", "all,2026-10-07 10:00:00,3", "time,2026-02-30,0"})
    void timeLikeSupportsDateFragmentsTimeAndCopiedTimestamp(String type, String keyword, int count) throws Exception {
        sample(START, 28.5f, 65, 534);
        sample(START.plusDays(1), 30, 70, 600);
        mvc.perform(get("/api/sensors/history").param("search_type", type).param("search", keyword)
                        .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(count))
                .andExpect(jsonPath("$.data.data.length()").value(count));
    }

    @Test
    void queryTreatsWildcardsEscapeCharacterAndQuotesAsLiteralInput() {
        var sensor = sensors.findById(1).orElseThrow();
        sensor.setName("Literal_%!'");
        sensors.saveAndFlush(sensor);
        sample(START, 30, 60, 650);
        sample(START.plusMinutes(1), 31, 61, 651);
        for (String keyword : List.of("_", "%", "!", "'")) {
            var page = service.getHistory(1, 1, keyword, "all", "asc");
            assertThat(page.getTotalRecords()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getData()).hasSize(1);
            assertThat(page.getData().getFirst().getSensorName()).isEqualTo("Literal_%!'");
        }
        assertThat(service.getHistory(1, 10, "' OR 1=1 --", "all", "asc").getTotalRecords()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "limit,0", "limit,101", "sort,wrong",
            "search_type,Nhiệt", "search_type,wrong", "search_type,value", "search_type,Temperature", "page,abc"})
    void invalidQueryParametersReturn400(String field, String value) throws Exception {
        mvc.perform(get("/api/sensors/history").param(field, value)
                .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void historyEndpointAcceptsFrontendQueryAndReturnsPaginationPayload() throws Exception {
        sample(START, 30.5f, 83, 850);
        mvc.perform(get("/api/sensors/history").param("search_type", "temperature").param("search", "30.5")
                .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(1))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.current_page").value(1))
                .andExpect(jsonPath("$.data.data[0].sensor_name").value("Nhiệt Độ"))
                .andExpect(jsonPath("$.data.data[0].created_at").value("2026-10-07T10:00:00+07:00"));
        mvc.perform(get("/api/sensors/history").param("search", "x".repeat(101))
                .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"all,30.5,3", "temperature,30.5,1", "humidity,30.5,1", "light,30.5,1",
            "time,2026-10-07 10:00:00,3"})
    void singleSearchTypeSelectsOnlyRequestedField(String type, String keyword, int count) throws Exception {
        sample(START, 30.5f, 30.5f, 30.5f);
        mvc.perform(get("/api/sensors/history").param("search_type", type).param("search", keyword)
                        .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(count))
                .andExpect(jsonPath("$.data.data.length()").value(count));
        var rows = service.getHistory(1, 10, keyword, type, "desc").getData();
        if (count == 1) {
            String expected = switch (type) {
                case "temperature" -> "Nhiệt Độ";
                case "humidity" -> "Độ Ẩm";
                default -> "Ánh Sáng";
            };
            assertThat(rows).extracting(row -> row.getSensorName()).containsOnly(expected);
        }
    }

    @Test
    void missingSearchTypeDefaultsToAllAndServiceRejectsLegacyMode() throws Exception {
        sample(START, 30.5f, 30.5f, 30.5f);
        mvc.perform(get("/api/sensors/history").param("search", "30.5")
                        .header("Authorization", "Bearer " + tokens.generateTokenFromUsername("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(3));
        assertThatThrownBy(() -> service.getHistory(1, 10, "30.5", "value", "desc"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
