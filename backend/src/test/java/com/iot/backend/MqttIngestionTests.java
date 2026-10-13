package com.iot.backend;

import com.iot.backend.dto.mqtt.SensorMeasurementPayload;
import com.iot.backend.config.MqttConfig;
import com.iot.backend.service.HistoryService;
import com.iot.backend.service.MqttService;
import com.iot.backend.service.SensorService;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttIngestionTests {
    private final IMqttClient client = mock(IMqttClient.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final SensorService measurements = mock(SensorService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MqttConnectOptions options = new MqttConnectOptions();
    private final MqttService mqtt = new MqttService(client, options, mapper, measurements,
            mock(HistoryService.class), executor);

    @Test
    void connectionConfigurationUsesOneRetryOwnerDurableSessionAndBoundedWaits() throws Exception {
        var config = new MqttConfig();
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://127.0.0.1:1");
        ReflectionTestUtils.setField(config, "clientId", "configuration-test");
        ReflectionTestUtils.setField(config, "username", "test");
        ReflectionTestUtils.setField(config, "password", "test");
        var settings = config.mqttConnectOptions();
        assertThat(settings.isAutomaticReconnect()).isFalse();
        assertThat(settings.isCleanSession()).isFalse();
        assertThat(settings.getConnectionTimeout()).isEqualTo(10);
        try (var configuredClient = config.mqttClient()) {
            assertThat(((MqttClient) configuredClient).getTimeToWait()).isEqualTo(10_000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "broken", "{\"temperature\":1,\"humidity\":2}",
            "{\"temperature\":\"1\",\"humidity\":2,\"light\":3}",
            "{\"temperature\":true,\"humidity\":2,\"light\":3}",
            "{\"temperature\":null,\"humidity\":2,\"light\":3}",
            "{\"temperature\":1e100,\"humidity\":2,\"light\":3}",
            "{\"temperature\":1,\"humidity\":-1,\"light\":3}",
            "{\"temperature\":1,\"humidity\":100.000001,\"light\":3}",
            "{\"temperature\":1,\"humidity\":2,\"light\":-1}",
            "{\"temperature\":1,\"humidity\":2,\"light\":3} {}",
            "{\"temperature\":1,\"temperature\":2,\"humidity\":2,\"light\":3}"})
    void malformedPayloadNeverReachesPersistence(String payload) {
        receive(payload);
        verifyNoInteractions(measurements);
    }

    @Test
    void acceptsBoundaryValuesAndIgnoresUnrelatedUtf8Fields() {
        receive("{\"temperature\":-10,\"humidity\":100,\"light\":0,\"note\":\"mẫu đo\"}");
        verify(measurements).saveMeasurement(new SensorMeasurementPayload(-10, 100, 0));
        receive("{\"temperature\":25,\"humidity\":0,\"light\":1}");
        verify(measurements).saveMeasurement(new SensorMeasurementPayload(25, 0, 1));
    }

    @Test
    void rejectsInvalidUtf8() {
        mqtt.messageArrived("iot/sensor/data", new MqttMessage(new byte[]{(byte) 0xc3, 0x28}));
        verifyNoInteractions(measurements);
    }

    @Test
    void databaseFailureIsNotAcknowledgedAsSuccessfulIngestion() {
        doThrow(new IllegalStateException("database unavailable")).when(measurements).saveMeasurement(any());
        assertThatThrownBy(() -> receive("{\"temperature\":1,\"humidity\":2,\"light\":3}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retriesInitialFailureAndResubscribesAfterConnectionLoss() throws Exception {
        var task = scheduledTask();
        when(client.isConnected()).thenReturn(false);
        doThrow(new MqttException(32103)).doAnswer(invocation -> {
            when(client.isConnected()).thenReturn(true);
            mqtt.connectComplete(false, "tcp://localhost");
            return null;
        }).when(client).connect(options);
        task.run();
        verify(client, never()).subscribe(any(String[].class), any(int[].class));
        task.run();
        verify(client).subscribe(new String[]{"iot/sensor/data", "iot/status"}, new int[]{1, 1});
        mqtt.connectionLost(null);
        when(client.isConnected()).thenReturn(false);
        task.run();
        verify(client, times(3)).connect(options);
        verify(client, times(2)).subscribe(any(String[].class), any(int[].class));
    }

    @Test
    void retriesFailedSubscriptionWithoutReconnectingHealthyClient() throws Exception {
        when(client.isConnected()).thenReturn(true);
        doThrow(new MqttException(128)).doNothing().when(client).subscribe(any(String[].class), any(int[].class));
        var task = scheduledTask();
        task.run(); task.run(); task.run();
        verify(client, never()).connect(any());
        verify(client, times(2)).subscribe(any(String[].class), any(int[].class));
    }

    @Test
    void shutdownClosesDisconnectedClientAndPreventsReconnect() throws Exception {
        var task = scheduledTask();
        mqtt.destroy();
        task.run();
        verify(executor).shutdownNow();
        verify(client).close();
        verify(client, never()).connect(any());
    }

    @Test
    void shutdownStillClosesWhenDisconnectFails() throws Exception {
        when(client.isConnected()).thenReturn(true);
        doThrow(new MqttException(32103)).when(client).disconnect();
        mqtt.destroy();
        verify(client).close();
    }

    @Test
    void controlPayloadUsesJsonEscapingUtf8QosOneAndNoRetain() throws Exception {
        when(client.isConnected()).thenReturn(true);
        mqtt.publishDeviceControl("đèn\"", "ON", 12);
        var message = ArgumentCaptor.forClass(MqttMessage.class);
        verify(client).publish(eq("iot/control"), message.capture());
        assertThat(message.getValue().getQos()).isEqualTo(1);
        assertThat(message.getValue().isRetained()).isFalse();
        var json = mapper.readTree(message.getValue().getPayload());
        assertThat(json.get("device").asString()).isEqualTo("đèn\"");
        assertThat(json.get("cmd").asString()).isEqualTo("ON");
        assertThat(json.get("command_id").asInt()).isEqualTo(12);
    }

    @Test
    void offlinePublishFailsClearly() {
        assertThatThrownBy(() -> mqtt.publishDeviceControl("led", "ON", 12)).isInstanceOf(MqttException.class);
    }

    private Runnable scheduledTask() {
        mqtt.init();
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(task.capture(), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        return task.getValue();
    }

    private void receive(String payload) {
        mqtt.messageArrived("iot/sensor/data", new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
