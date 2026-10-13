package com.iot.backend;

import com.iot.backend.dto.mqtt.DeviceAcknowledgement;
import com.iot.backend.dto.request.DeviceControlRequest;
import com.iot.backend.entity.Device;
import com.iot.backend.entity.History;
import com.iot.backend.entity.User;
import com.iot.backend.repository.*;
import com.iot.backend.security.JwtTokenProvider;
import com.iot.backend.service.*;
import com.iot.backend.scheduler.DeviceCommandTimeoutTask;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions/security/database; only the network publisher is mocked. No test-wide transaction. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:iot_device_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false", "spring.jpa.defer-datasource-initialization=true",
        "spring.sql.init.mode=always", "mqtt.enabled=false", "devices.command.timeout-scheduler.enabled=false"
})
@AutoConfigureMockMvc
public class DeviceApiTests {
    @Autowired DeviceService service;
    @Autowired HistoryService historyService;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired HistoryRepository histories;
    @Autowired DeviceRepository devices;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean MqttService mqtt;

    @BeforeEach
    void prepare() {
        histories.deleteAll();
        if (environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:")) {
            jdbc.execute("CREATE ALIAS IF NOT EXISTS date_format FOR 'com.iot.backend.SensorApiTests.mysqlDateFormat'");
        }
        for (String code : List.of("led", "fan", "ac")) {
            if (!devices.existsByCode(code)) devices.saveAndFlush(Device.builder().code(code).name(code).build());
        }
        when(mqtt.isReadyForControl()).thenReturn(true);
    }

    @AfterEach
    void cleanup() { histories.deleteAll(); }

    private String token() { return tokens.generateTokenFromUsername("admin"); }
    private int send(String device, String action) { return service.control(new DeviceControlRequest(device, action), "admin").commandId(); }
    private History row(int id) { return histories.findById(id).orElseThrow(); }
    private void ack(int id, String device, String status, String actual) {
        historyService.handleAcknowledgement(new DeviceAcknowledgement(id, device, status, actual));
    }
    private String state(String code) {
        return service.getStatuses().stream().filter(item -> item.device().equals(code)).findFirst().orElseThrow().status();
    }
    private void age(int id) {
        History history = row(id);
        history.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMinutes(1));
        histories.saveAndFlush(history);
    }

    @Test
    void statusUnknownUntilAckAndPendingDoesNotChangeHardwareState() throws Exception {
        mvc.perform(get("/api/devices/status").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200)).andExpect(jsonPath("$.data.length()").value(3));
        assertThat(service.getStatuses()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("UNKNOWN");
            assertThat(item.lastSeen()).isNull();
        });
        int id = send("led", "ON");
        assertThat(state("led")).isEqualTo("UNKNOWN");
        ack(id, "led", "pending", "ON");
        assertThat(row(id).getStatus()).isEqualTo("pending");
        assertThat(state("led")).isEqualTo("UNKNOWN");
        ack(id, "led", "ON", "");
        assertThat(row(id).getStatus()).isEqualTo("success");
        assertThat(state("led")).isEqualTo("ON");
    }

    @Test
    void apiReturns202AndHistoryUsesAuthenticatedUserNotBody() throws Exception {
        var result = mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content("{\"device\":\"led\",\"action\":\"ON\",\"user\":\"forged\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.data.command_status").value("pending"))
                .andExpect(jsonPath("$.data.command_id").isNumber()).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.size()).isEqualTo(3);
        int id = json.get("data").get("command_id").asInt();
        verify(mqtt).publishDeviceControl("led", "ON", id);
        assertThat(row(id).getUser().getId()).isEqualTo(users.findByUsername("admin").orElseThrow().getId());
    }

    @Test
    void pendingIsCommittedBeforePublishAndEarlyAckIsNeverReverted() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int id = invocation.getArgument(2);
            assertThat(row(id).getStatus()).isEqualTo("pending");
            ack(id, "led", "success", "ON");
            return null;
        }).when(mqtt).publishDeviceControl(eq("led"), eq("ON"), anyInt());
        int id = send("led", "ON");
        assertThat(row(id).getStatus()).isEqualTo("success");
    }

    @Test
    void publishFailureMarksFailedAndDoesNotChangeConfirmedState() throws Exception {
        int first = send("led", "ON"); ack(first, "led", "ON", "");
        doThrow(new MqttException(32103)).when(mqtt).publishDeviceControl(eq("led"), eq("OFF"), anyInt());
        mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content("{\"device\":\"led\",\"action\":\"OFF\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
        assertThat(histories.findAll()).extracting(History::getStatus).containsExactlyInAnyOrder("success", "failed");
        assertThat(state("led")).isEqualTo("ON");
    }

    @Test
    void failureAfterEarlyAckCannotDowngradeSuccess() throws Exception {
        doAnswer(invocation -> {
            ack(invocation.getArgument(2), "led", "ON", "");
            throw new MqttException(32103);
        }).when(mqtt).publishDeviceControl(eq("led"), eq("ON"), anyInt());
        assertThatThrownBy(() -> send("led", "ON")).isInstanceOf(ResponseStatusException.class);
        assertThat(histories.findAll().getFirst().getStatus()).isEqualTo("success");
        assertThat(state("led")).isEqualTo("ON");
    }

    @Test
    void offlineBrokerReturns503WithoutCreatingHistory() throws Exception {
        when(mqtt.isReadyForControl()).thenReturn(false);
        mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content("{\"device\":\"led\",\"action\":\"ON\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertThat(histories.count()).isZero();
        verify(mqtt, never()).publishDeviceControl(anyString(), anyString(), anyInt());
    }

    @Test
    void duplicateLateWrongDeviceAndUnknownIdAcksDoNotChangeNewCommand() {
        int first = send("led", "ON"); ack(first, "led", "ON", "");
        int second = send("led", "OFF");
        ack(first, "led", "OFF", "");
        ack(second, "fan", "OFF", "");
        ack(Integer.MAX_VALUE, "led", "OFF", "");
        assertThat(row(first).getStatusReceived()).isEqualTo("ON");
        assertThat(row(second).getStatus()).isEqualTo("pending");
        assertThat(state("led")).isEqualTo("ON");
        ack(second, "led", "OFF", "");
        assertThat(state("led")).isEqualTo("OFF");
        assertThat(histories.count()).isEqualTo(2);
    }

    @Test
    void mismatchAckIsFailedButActualPhysicalStateIsRecorded() {
        int id = send("led", "ON");
        ack(id, "led", "success", "OFF");
        assertThat(row(id).getStatus()).isEqualTo("failed");
        assertThat(state("led")).isEqualTo("OFF");
    }

    @Test
    void failedAckWithoutPhysicalStateKeepsPreviousConfirmation() {
        int first = send("led", "ON"); ack(first, "led", "ON", "");
        int second = send("led", "OFF"); ack(second, "led", "failed", "");
        assertThat(row(second).getStatusReceived()).isNull();
        assertThat(state("led")).isEqualTo("ON");
    }

    @Test
    void timeoutRecoveryRejectsLateAckAndAllowsAnotherCommand() {
        int first = send("led", "ON"); age(first);
        new DeviceCommandTimeoutTask(historyService).expirePendingCommands();
        assertThat(row(first).getStatus()).isEqualTo("failed");
        int second = send("led", "OFF");
        ack(first, "led", "ON", "");
        assertThat(row(second).getStatus()).isEqualTo("pending");
        assertThat(state("led")).isEqualTo("UNKNOWN");
        ack(second, "led", "OFF", "");
        assertThat(state("led")).isEqualTo("OFF");
    }

    @Test
    void ackPastDeadlineCannotWinEvenBeforeTimeoutWorkerRuns() {
        int id = send("led", "ON"); age(id);
        ack(id, "led", "ON", "");
        assertThat(row(id).getStatus()).isEqualTo("failed");
        assertThat(row(id).getStatusReceived()).isNull();
    }

    @Test
    void reserveExpiresStalePendingWithoutWaitingForWorker() {
        int first = send("led", "ON"); age(first);
        int second = send("led", "OFF");
        assertThat(row(first).getStatus()).isEqualTo("failed");
        assertThat(row(second).getStatus()).isEqualTo("pending");
    }

    @Test
    void secondPendingRequestReturns409WhileAnotherDeviceCanProceed() throws Exception {
        send("led", "ON");
        mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content("{\"device\":\"led\",\"action\":\"OFF\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        send("fan", "ON");
        assertThat(histories.count()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsHaveOnlyOnePendingWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<Integer> request = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { send("led", "ON"); return 202; }
                catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
            };
            var one = executor.submit(request);
            var two = executor.submit(request);
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
            assertThat(histories.count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "bad", "{\"device\":\"unknown\",\"action\":\"ON\"}",
            "{\"device\":\"led\",\"action\":\"on\"}", "{\"device\":\"led\",\"action\":null}"})
    void invalidRequestReturns400(String body) throws Exception {
        mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertThat(histories.count()).isZero();
    }

    @Test
    void absentDeviceIs404AndBothEndpointsRequireAuthentication() throws Exception {
        var device = devices.findByCode("ac").orElseThrow(); devices.delete(device);
        mvc.perform(post("/api/devices/control").header("Authorization", "Bearer " + token())
                        .contentType("application/json").content("{\"device\":\"ac\",\"action\":\"ON\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/devices/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/devices/control").contentType("application/json")
                .content("{\"device\":\"led\",\"action\":\"ON\"}")).andExpect(status().isUnauthorized());
    }

    private static final LocalDateTime HISTORY_TIME = LocalDateTime.of(2026, 10, 7, 10, 30, 15);

    @ParameterizedTest
    @CsvSource({"ON,ON,success", "ON,OFF,failed", "OFF,OFF,success", "OFF,ON,failed"})
    void firmwareOnOffReplyIsComparedWithRequestedAction(String action, String received, String outcome) {
        int id = send("led", action);
        assertThat(row(id).getStatus()).isEqualTo("pending");
        ack(id, "led", received, "");
        assertThat(row(id).getStatus()).isEqualTo(outcome);
        assertThat(row(id).getStatusReceived()).isEqualTo(received);
    }

    private History history(String code, String action, String outcome, String received,
                            boolean automatic, LocalDateTime time) {
        return histories.saveAndFlush(History.builder().device(devices.findByCode(code).orElseThrow())
                .user(automatic ? null : users.findByUsername("admin").orElseThrow())
                .action(action).status(outcome).statusReceived(received).createdAt(time).build());
    }

    @Test
    void historyMapsExistingColumnsAndRetainsNullAckAndAutomaticOperator() throws Exception {
        var pending = history("led", "ON", "pending", null, true, HISTORY_TIME);
        var response = mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.data[0].id").value(pending.getId()))
                .andExpect(jsonPath("$.data.data[0].command_id").value(pending.getId()))
                .andExpect(jsonPath("$.data.data[0].device_name").value(pending.getDevice().getName()))
                .andExpect(jsonPath("$.data.data[0].action_sent").value("ON"))
                .andExpect(jsonPath("$.data.data[0].command_status").value("pending"))
                .andExpect(jsonPath("$.data.data[0].operator").value("Hệ thống (Auto)"))
                .andExpect(jsonPath("$.data.data[0].executed_at").value("2026-10-07T10:30:15+07:00"))
                .andReturn();
        var json = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("data").get("data").get(0).has("status_received")).isTrue();
        assertThat(json.get("data").get("data").get(0).get("status_received").isNull()).isTrue();
    }

    @Test
    void historyCombinesFiltersAndCountQueryWithoutConfusingOutcomeWithActualState() throws Exception {
        history("led", "ON", "success", "ON", false, HISTORY_TIME);
        var failed = history("led", "ON", "failed", "OFF", false, HISTORY_TIME);
        history("fan", "ON", "failed", "OFF", false, HISTORY_TIME);
        history("led", "OFF", "failed", "OFF", false, HISTORY_TIME);
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token())
                        .param("device", "Đèn LED").param("action", "ON")
                        .param("command_status", "failed").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(1))
                .andExpect(jsonPath("$.data.data[0].id").value(failed.getId()));
        assertThat(historyService.getHistory(1, 1, "", "", "failed", "", "desc").getTotalRecords()).isEqualTo(3);
    }

    @Test
    void historySortsByTimeThenIdAndPagesBeyondEndStayEmpty() {
        var first = history("fan", "ON", "success", "ON", false, HISTORY_TIME.plusDays(1));
        var older = history("led", "ON", "success", "ON", false, HISTORY_TIME);
        var third = history("ac", "ON", "success", "ON", false, HISTORY_TIME.plusDays(1));
        var asc = historyService.getHistory(1, 10, "", "", "", "", "asc");
        assertThat(asc.getData()).extracting(item -> item.id()).containsExactly(older.getId(), first.getId(), third.getId());
        var page = historyService.getHistory(2, 1, "", "", "", "", "desc");
        assertThat(page.getData()).extracting(item -> item.id()).containsExactly(first.getId());
        assertThat(page.getTotalRecords()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(3);
        var beyond = historyService.getHistory(4, 1, "", "", "", "", "desc");
        assertThat(beyond.getData()).isEmpty();
        assertThat(beyond.getCurrentPage()).isEqualTo(4);
    }

    @Test
    void historyOutcomeFilterDoesNotDependOnOnOffAckOrLegacyStatusParameter() throws Exception {
        history("led", "ON", "failed", "OFF", false, HISTORY_TIME);
        history("led", "ON", "failed", null, false, HISTORY_TIME);
        history("led", "ON", "pending", null, false, HISTORY_TIME);
        // status đã bỏ khỏi hợp đồng: Spring không bind tham số cũ, không lọc ngầm theo ACK.
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token())
                        .param("device", "Đèn LED").param("command_status", "failed")
                        .param("status", "ON").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.data[0].command_status").value("failed"));
    }

    @Test
    void historyEmptyResultAlwaysHasOnePage() throws Exception {
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token()).param("page", "9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.length()").value(0))
                .andExpect(jsonPath("$.data.total_records").value(0))
                .andExpect(jsonPath("$.data.total_pages").value(1)).andExpect(jsonPath("$.data.current_page").value(1));
    }

    @Test
    void historySearchesUsernameAndAutomaticOperatorCaseInsensitively() {
        history("led", "ON", "success", "ON", false, HISTORY_TIME);
        var automatic = history("fan", "OFF", "pending", null, true, HISTORY_TIME);
        var admin = historyService.getHistory(1, 10, "", "", "", " ADMIN ", "desc");
        assertThat(admin.getTotalRecords()).isEqualTo(1);
        assertThat(admin.getData().getFirst().operator()).isEqualTo("admin");
        assertThat(historyService.getHistory(1, 10, "", "", "", "auto", "desc").getData())
                .extracting(item -> item.id()).containsExactly(automatic.getId());
    }

    @Test
    void historyDateLikeSearchKeepsDateBoundariesAndPartialTime() {
        history("led", "ON", "pending", null, true, HISTORY_TIME.toLocalDate().atStartOfDay().minusSeconds(1));
        history("led", "ON", "pending", null, true, HISTORY_TIME.toLocalDate().atStartOfDay());
        history("led", "ON", "pending", null, true, HISTORY_TIME);
        history("led", "ON", "pending", null, true, HISTORY_TIME.toLocalDate().plusDays(1).atStartOfDay());
        assertThat(historyService.getHistory(1, 1, "", "", "", "2026-10-07", "asc").getTotalRecords()).isEqualTo(2);
        assertThat(historyService.getHistory(1, 10, "", "", "", "10:30", "desc").getTotalRecords()).isEqualTo(1);
        assertThat(historyService.getHistory(1, 10, "", "", "", "2026-02-30", "desc").getData()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-07", "2026-10", "10-07", "10:30", "2026-10-07 10:30:15", "ADMIN", "auto"})
    void deviceHistoryLikeSearchSupportsDateFragmentsCopiedTimeAndOperator(String keyword) throws Exception {
        history("led", "ON", "success", "ON", false, HISTORY_TIME);
        history("fan", "OFF", "pending", null, true, HISTORY_TIME.plusDays(1));
        int expected = List.of("2026-10", "10:30").contains(keyword) ? 2 : 1;
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token())
                        .param("search", keyword).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_records").value(expected))
                .andExpect(jsonPath("$.data.total_pages").value(expected))
                .andExpect(jsonPath("$.data.data.length()").value(1));
    }

    @Test
    void historySearchEscapesWildcardsAndBindsQuotes() {
        var special = users.saveAndFlush(User.builder().username("history%_!O'Neil").password("unused-test-value").build());
        try {
            var h = history("led", "ON", "pending", null, false, HISTORY_TIME);
            h.setUser(special); histories.saveAndFlush(h);
            history("fan", "ON", "pending", null, false, HISTORY_TIME);
            for (String keyword : List.of("%", "_", "!", "O'Neil")) {
                assertThat(historyService.getHistory(1, 1, "", "", "", keyword, "desc").getTotalRecords()).isEqualTo(1);
            }
            assertThat(historyService.getHistory(1, 10, "", "", "", "' OR 1=1 --", "desc").getData()).isEmpty();
        } finally {
            histories.deleteAll(); users.delete(special);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=bad", "limit=0", "limit=101", "device=led", "action=on",
            "command_status=SUCCESS", "command_status=ON", "sort=invalid"})
    void historyRejectsInvalidQuery(String query) throws Exception {
        String[] parts = query.split("=", 2);
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token()).param(parts[0], parts[1]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void historyRejectsOversizedSearchAndRequiresJwt() throws Exception {
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer " + token()).param("search", "x".repeat(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/devices/history")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/devices/history").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> historyService.getHistory(0, 10, "", "", "", "", "desc"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
