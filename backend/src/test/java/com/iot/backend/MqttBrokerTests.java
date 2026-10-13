package com.iot.backend;

import com.iot.backend.dto.mqtt.SensorMeasurementPayload;
import com.iot.backend.service.HistoryService;
import com.iot.backend.service.MqttService;
import com.iot.backend.service.SensorService;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Optional real-broker test: owns a separate Mosquitto process and an ephemeral local port. */
@EnabledIfSystemProperty(named = "iot.mqtt.tests", matches = "true")
class MqttBrokerTests {
    @Test
    void recoversFromInitialOfflineBrokerAndBrokerRestart() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String uri = "tcp://127.0.0.1:" + port;
        var subscriptions = new AtomicReference<>(new CountDownLatch(1));
        var firstAttempt = new CountDownLatch(1);
        var lost = new CountDownLatch(1);
        var received = new LinkedBlockingQueue<SensorMeasurementPayload>();
        var persistence = mock(SensorService.class);
        doAnswer(invocation -> {
            received.add(invocation.getArgument(0));
            return null;
        }).when(persistence).saveMeasurement(any());
        var options = new MqttConnectOptions();
        options.setConnectionTimeout(1);
        options.setKeepAliveInterval(2);
        options.setAutomaticReconnect(false);
        options.setCleanSession(false);
        var executor = Executors.newSingleThreadScheduledExecutor();
        var client = spy(new MqttClient(uri, "test-subscriber-" + UUID.randomUUID(), new MemoryPersistence()));
        doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            finally { firstAttempt.countDown(); }
        }).when(client).connect(options);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            subscriptions.get().countDown();
            return result;
        }).when(client).subscribe(any(String[].class), any(int[].class));
        var mqtt = spy(new MqttService(client, options, JsonMapper.builder().build(), persistence,
                mock(HistoryService.class), executor));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            lost.countDown();
            return null;
        }).when(mqtt).connectionLost(any());
        Process broker = null;
        try {
            mqtt.init();
            assertThat(firstAttempt.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.isConnected()).isFalse();
            broker = startBroker(port);
            assertThat(subscriptions.get().await(12, TimeUnit.SECONDS)).isTrue();
            publish(uri, "{\"temperature\":\"bad\",\"humidity\":50,\"light\":2}",
                    "{\"temperature\":25,\"humidity\":50,\"light\":2}");
            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(new SensorMeasurementPayload(25, 50, 2));
            assertThat(received).isEmpty();

            stopBroker(broker);
            assertThat(lost.await(8, TimeUnit.SECONDS)).isTrue();
            subscriptions.set(new CountDownLatch(1));
            broker = startBroker(port);
            assertThat(subscriptions.get().await(12, TimeUnit.SECONDS)).isTrue();
            publish(uri, "{\"temperature\":26,\"humidity\":51,\"light\":3}");
            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(new SensorMeasurementPayload(26, 51, 3));
        } finally {
            mqtt.destroy();
            stopBroker(broker);
        }
    }

    private Process startBroker(int port) throws Exception {
        String executable = System.getProperty("iot.mqtt.executable", "mosquitto");
        Process process = new ProcessBuilder(executable, "-p", Integer.toString(port))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Worker retries every 5 seconds; the assertion above verifies the broker actually starts.
        return process;
    }

    private void stopBroker(Process broker) throws Exception {
        if (broker == null || !broker.isAlive()) return;
        broker.destroy();
        if (!broker.waitFor(5, TimeUnit.SECONDS)) {
            broker.destroyForcibly();
            assertThat(broker.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void publish(String uri, String... payloads) throws Exception {
        try (var publisher = new MqttClient(uri, "test-publisher-" + UUID.randomUUID(), new MemoryPersistence())) {
            publisher.connect();
            try {
                for (String payload : payloads) publisher.publish("iot/sensor/data", payload.getBytes(StandardCharsets.UTF_8), 1, false);
            } finally {
                publisher.disconnect();
            }
        }
    }
}
