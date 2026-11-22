package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

/** {@code /ws/telemetry} con la corrida automática a ×10: hello, vistas, eventos de falla y esquemas. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"twin.autostart.scenario=nominal", "twin.autostart.speed-factor=10"})
class WebSocketIntegrationTest {

    private static final long TIMEOUT_S = 5;
    private static final int MESSAGES = 15;

    @Value("${local.server.port}")
    int port;

    private final List<WebSocketSession> sessions = new ArrayList<>();

    @AfterEach
    void closeSessions() throws Exception {
        for (WebSocketSession session : sessions) {
            session.close();
        }
    }

    @Test
    void eachViewGetsHelloThenConsecutiveValidMessagesAndFaultsOnlyWithGroundTruth() throws Exception {
        BlockingQueue<String> publicView = subscribe("");
        BlockingQueue<String> groundTruthView = subscribe("?groundTruth=true");
        JsonNode publicHello = next(publicView);
        JsonNode groundTruthHello = next(groundTruthView);
        assertValid("hello", publicHello);
        assertValid("hello", groundTruthHello);
        assertThat(publicHello.path("snapshot").has("groundTruth")).isFalse();
        assertThat(groundTruthHello.path("snapshot").has("groundTruth")).isTrue();

        HttpResponse<String> injected = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/twin/sim/faults"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"SENSOR_NOISE\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(injected.statusCode()).isEqualTo(201);

        List<JsonNode> publicMessages = drain(publicView, publicHello);
        List<JsonNode> groundTruthMessages = drain(groundTruthView, groundTruthHello);

        assertThat(eventTypes(groundTruthMessages)).contains("FAULT_INJECTED");
        assertThat(eventTypes(publicMessages)).doesNotContain("FAULT_INJECTED");
        assertThat(publicMessages)
                .filteredOn(m -> m.get("type").asString().equals("telemetry"))
                .allSatisfy(m -> assertThat(m.has("groundTruth")).isFalse())
                .isNotEmpty();
        assertThat(groundTruthMessages)
                .filteredOn(m -> m.get("type").asString().equals("telemetry"))
                .allSatisfy(m -> assertThat(m.has("groundTruth")).isTrue());
    }

    /** Lee hasta ver la falla en la vista groundTruth (o {@value #MESSAGES} mensajes); valida esquema y seq. */
    private static List<JsonNode> drain(BlockingQueue<String> queue, JsonNode hello) throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        long expectedSeq = hello.get("seq").asLong() + 1;
        while (messages.size() < MESSAGES) {
            JsonNode message = next(queue);
            assertValid(message.get("type").asString(), message);
            assertThat(message.get("seq").asLong()).as(message.toString()).isEqualTo(expectedSeq++);
            messages.add(message);
            if (eventTypes(messages).contains("FAULT_INJECTED")) {
                break;
            }
        }
        return messages;
    }

    private static List<String> eventTypes(List<JsonNode> messages) {
        return messages.stream()
                .filter(m -> m.get("type").asString().equals("event"))
                .map(m -> m.get("eventType").asString())
                .toList();
    }

    private BlockingQueue<String> subscribe(String query) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                                messages.add(message.getPayload());
                            }
                        },
                        "ws://localhost:" + port + "/ws/telemetry" + query)
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        sessions.add(session);
        return messages;
    }

    private static JsonNode next(BlockingQueue<String> messages) throws InterruptedException {
        String message = messages.poll(TIMEOUT_S, TimeUnit.SECONDS);
        assertThat(message).as("mensaje en %d s", TIMEOUT_S).isNotNull();
        return Contracts.JSON.readTree(message);
    }

    private static void assertValid(String schema, JsonNode message) {
        assertThat(Contracts.errors(Contracts.schema(schema), message))
                .as(message.toString())
                .isEmpty();
    }
}
