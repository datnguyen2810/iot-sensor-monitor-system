package com.iot.backend;

import com.iot.backend.dto.request.DeviceControlRequest;
import com.iot.backend.repository.HistoryRepository;
import com.iot.backend.repository.UserRepository;
import com.iot.backend.repository.DataSensorRepository;
import com.iot.backend.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.iot.backend.service.DeviceService;
import com.iot.backend.service.MqttService;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;

/** Real MQTT transport + Spring transactions + H2 + simulated firmware echoing History.id. */
@EnabledIfSystemProperty(named = "iot.mqtt.tests", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:iot_mqtt_device_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false", "spring.jpa.defer-datasource-initialization=true", "spring.sql.init.mode=always",
        "mqtt.enabled=true", "mqtt.username=", "mqtt.password=", "devices.command.timeout-scheduler.enabled=true"
})
class MqttDeviceFlowTests {
    private static Process broker;
    private static String uri;
    @Autowired DeviceService devices;
    @Autowired MqttService mqtt;
    @Autowired HistoryRepository histories;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired DataSensorRepository records;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int httpPort;

    @DynamicPropertySource
    static void isolatedBroker(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        uri = "tcp://127.0.0.1:" + port;
        broker = new ProcessBuilder(System.getProperty("iot.mqtt.executable", "mosquitto"), "-p", Integer.toString(port))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        registry.add("mqtt.broker.url", () -> uri);
        String id = "flow-backend-" + UUID.randomUUID();
        registry.add("mqtt.client.id", () -> id);
    }

    @BeforeEach
    void ready() throws Exception {
        histories.deleteAll();
        jdbc.execute("CREATE ALIAS IF NOT EXISTS date_format FOR 'com.iot.backend.SensorApiTests.mysqlDateFormat'");
        await(mqtt::isReadyForControl, 12);
    }

    @Test
    void commandIdRoundTripUpdatesHistoryAndConfirmedDeviceState() throws Exception {
        try (var firmware = new MqttAsyncClient(uri, "flow-firmware-" + UUID.randomUUID(), new MemoryPersistence())) {
            firmware.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) { }
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    var command = mapper.readTree(message.getPayload());
                    String ack = mapper.writeValueAsString(Map.of("device", command.get("device").asString(),
                            "status", command.get("cmd").asString(), "command_id", command.get("command_id").asInt()));
                    firmware.publish("iot/status", ack.getBytes(StandardCharsets.UTF_8), 1, false);
                }
            });
            firmware.connect().waitForCompletion(5000);
            firmware.subscribe("iot/control", 1).waitForCompletion(5000);
            try {
                var receipt = devices.control(new DeviceControlRequest("led", "ON"), "admin");
                await(() -> histories.findById(receipt.commandId()).orElseThrow().getStatus().equals("success"), 5);
                assertThat(histories.findById(receipt.commandId()).orElseThrow().getStatusReceived()).isEqualTo("ON");
                assertThat(devices.getStatuses().stream().filter(item -> item.device().equals("led")).findFirst().orElseThrow().status())
                        .isEqualTo("ON");
            } finally { firmware.disconnect().waitForCompletion(5000); }
        }
    }

    @Test
    void scheduledTimeoutActuallyExpiresAnUnacknowledgedCommand() throws Exception {
        var receipt = devices.control(new DeviceControlRequest("led", "OFF"), "admin");
        await(() -> histories.findById(receipt.commandId()).orElseThrow().getStatus().equals("failed"), 10);
        assertThat(histories.findById(receipt.commandId()).orElseThrow().getStatusReceived()).isNull();
        assertThat(devices.getStatuses().stream().filter(item -> item.device().equals("led")).findFirst().orElseThrow().status())
                .isEqualTo("UNKNOWN");
    }

    @Test
    @EnabledIfSystemProperty(named = "iot.frontend.tests", matches = "true")
    void frontendJavaScriptRunsAgainstRealHttpAndSimulatedFirmware() throws Exception {
        String username = "frontend_smoke_test";
        String password = "isolated-test-only-password";
        var user = users.saveAndFlush(User.builder().username(username).password(passwords.encode(password)).build());
        records.deleteAll();
        try (var firmware = new MqttAsyncClient(uri, "http-firmware-" + UUID.randomUUID(), new MemoryPersistence())) {
            firmware.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) { }
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    var command = mapper.readTree(message.getPayload());
                    String ack = mapper.writeValueAsString(Map.of("device", command.get("device").asString(),
                            "status", command.get("cmd").asString(), "command_id", command.get("command_id").asInt()));
                    firmware.publish("iot/status", ack.getBytes(StandardCharsets.UTF_8), 1, false);
                }
            });
            firmware.connect().waitForCompletion(5000);
            firmware.subscribe("iot/control", 1).waitForCompletion(5000);
            try {
                firmware.publish("iot/sensor/data", "{\"temperature\":28.5,\"humidity\":65,\"light\":0}"
                        .getBytes(StandardCharsets.UTF_8), 1, false).waitForCompletion(5000);
                await(() -> records.count() == 3, 5);
                var builder = new ProcessBuilder(System.getProperty("iot.frontend.node", "node"), "../frontend/tests/http-smoke.mjs")
                        .redirectErrorStream(true);
                builder.environment().put("IOT_FRONTEND_SMOKE", mapper.writeValueAsString(Map.of(
                        "baseUrl", "http://127.0.0.1:" + httpPort, "username", username, "password", password)));
                String browserExecutable = System.getProperty("iot.frontend.browser");
                if (browserExecutable != null) builder.environment().put("IOT_FRONTEND_BROWSER", browserExecutable);
                var process = builder.start();
                try {
                    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("frontend smoke deadline").isTrue();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(process.exitValue()).as(output).isZero();
                    assertThat(output).contains("PASS: frontend JS");
                    if (browserExecutable != null) assertThat(output).contains("PASS: native headless browser");
                } finally {
                    if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
                }
            } finally { firmware.disconnect().waitForCompletion(5000); }
        } finally {
            histories.deleteAll(); records.deleteAll(); users.delete(user);
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @AfterAll
    static void stopBroker() throws Exception {
        if (broker != null && broker.isAlive()) {
            broker.destroy();
            if (!broker.waitFor(5, TimeUnit.SECONDS)) { broker.destroyForcibly(); broker.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
