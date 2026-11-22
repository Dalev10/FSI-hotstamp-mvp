package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.TwinApplication;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

/** Al apagar, los clientes reciben {@code RUN_ENDED{STOPPED}} y luego el cierre {@code 1001 GOING_AWAY}. */
@ExtendWith(OutputCaptureExtension.class)
class GracefulShutdownTest {

    private static final long TIMEOUT_S = 10;

    @Test
    void shuttingDownEndsTheRunAndClosesSessionsWithGoingAway(CapturedOutput output) throws Exception {
        // Contexto propio (no el de la caché de pruebas): se cierra aquí. Argumentos y no properties(), que son
        // valores por defecto y application.yml los pisaría.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TwinApplication.class)
                .run("--server.port=0", "--twin.autostart.scenario=nominal", "--twin.autostart.speed-factor=10");
        String port = context.getEnvironment().getProperty("local.server.port");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        new StandardWebSocketClient()
                .execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                                messages.add(message.getPayload());
                            }

                            @Override
                            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                                closed.complete(status);
                            }
                        },
                        "ws://localhost:" + port + "/ws/telemetry?groundTruth=true")
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        assertThat(messages.poll(TIMEOUT_S, TimeUnit.SECONDS)).contains("\"hello\"");

        context.close();

        assertThat(closed.get(TIMEOUT_S, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        JsonNode last = Contracts.JSON.readTree(
                messages.stream().reduce((first, second) -> second).orElseThrow());
        assertThat(Contracts.errors(Contracts.schema("event"), last)).isEmpty();
        assertThat(last.get("eventType").asString()).isEqualTo("RUN_ENDED");
        assertThat(last.get("data").get("reason").asString()).isEqualTo("STOPPED");
        assertThat(output.getAll()).contains("Corrida terminada: STOPPED"); // el log del cierre no se pierde
    }
}
