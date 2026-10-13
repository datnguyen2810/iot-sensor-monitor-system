package com.iot.backend;

import com.iot.backend.dto.mqtt.DeviceAcknowledgement;
import com.iot.backend.service.HistoryService;
import com.iot.backend.service.MqttService;
import com.iot.backend.service.SensorService;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MqttCommandStatusTests {
    private final HistoryService commands = mock(HistoryService.class);
    private final MqttService mqtt = new MqttService(mock(IMqttClient.class), new MqttConnectOptions(),
            JsonMapper.builder().build(), mock(SensorService.class), commands,
            mock(ScheduledExecutorService.class));

    @ParameterizedTest
    @ValueSource(strings = {"ON", "OFF", "success", "failed", "pending"})
    void validatedCorrelatedAckIsPassedToTransactionalHandler(String status) {
        receive("{\"device\":\"led\",\"command_id\":12,\"status\":\"" + status + "\"}");
        verify(commands).handleAcknowledgement(new DeviceAcknowledgement(12, "led", status, ""));
    }

    @Test
    void explicitPhysicalStateIsPreserved() {
        receive("{\"device\":\"led\",\"command_id\":12,\"status\":\"failed\",\"device_status\":\"OFF\"}");
        verify(commands).handleAcknowledgement(new DeviceAcknowledgement(12, "led", "failed", "OFF"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "bad",
            "{\"device\":\"led\",\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":0,\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":-1,\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":1.2,\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":\"12\",\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":2147483648,\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":12,\"status\":\"invalid\"}",
            "{\"device\":\"unknown\",\"command_id\":12,\"status\":\"ON\"}",
            "{\"device\":\"led\",\"command_id\":12,\"status\":\"success\",\"device_status\":null}",
            "{\"device\":\"led\",\"command_id\":12,\"status\":\"ON\",\"device_status\":\"OFF\"}",
            "{\"device\":\"led\",\"command_id\":12,\"status\":\"ON\"} {}",
            "{\"device\":\"led\",\"command_id\":12,\"command_id\":13,\"status\":\"ON\"}"})
    void malformedOrUncorrelatedAckCannotChangeCommands(String payload) {
        receive(payload);
        verifyNoInteractions(commands);
    }

    @Test
    void databaseFailurePropagatesToMqttInsteadOfSilentlyDroppingAck() {
        doThrow(new IllegalStateException("DB unavailable")).when(commands).handleAcknowledgement(any());
        assertThatThrownBy(() -> receive("{\"device\":\"led\",\"command_id\":12,\"status\":\"ON\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    private void receive(String payload) {
        mqtt.messageArrived("iot/status", new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
