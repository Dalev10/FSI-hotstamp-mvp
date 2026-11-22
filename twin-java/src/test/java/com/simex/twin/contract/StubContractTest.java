package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

/** Criterio de cierre de T1: con el perfil stub, lo que recibe un cliente cumple el contrato. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class StubContractTest {

    private static final long TIMEOUT_S = 5;

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
    void publicViewGetsHelloThenConsecutiveTelemetryWithoutGroundTruth() throws Exception {
        BlockingQueue<String> messages = subscribe("");

        JsonNode hello = next(messages);
        JsonNode first = next(messages);
        JsonNode second = next(messages);

        assertValid("hello", hello);
        assertValid("telemetry", first);
        assertValid("telemetry", second);
        assertThat(hello.path("snapshot").has("groundTruth")).isFalse();
        assertThat(first.has("groundTruth")).isFalse();
        assertThat(first.path("seq").asLong()).isEqualTo(hello.path("seq").asLong() + 1);
        assertThat(second.path("seq").asLong()).isEqualTo(first.path("seq").asLong() + 1);
    }

    @Test
    void groundTruthViewIncludesTheGroundTruthBlock() throws Exception {
        BlockingQueue<String> messages = subscribe("?groundTruth=true");

        JsonNode hello = next(messages);
        JsonNode telemetry = next(messages);

        assertValid("hello", hello);
        assertValid("telemetry", telemetry);
        assertThat(hello.path("snapshot").has("groundTruth")).isTrue();
        assertThat(telemetry.has("groundTruth")).isTrue();
    }

    @Test
    void commandsAreAcceptedWithAContractValidResult() throws Exception {
        HttpResponse<String> response =
                post("/api/twin/commands", Files.readString(Contracts.EXAMPLES.resolve("command.set-pid.json")));

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode result = Contracts.JSON.readTree(response.body());
        assertValid("command-result", result);
        assertThat(result.path("commandId").asString()).isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    }

    @Test
    void heartbeatsReturnNoContent() throws Exception {
        HttpResponse<String> response =
                post("/api/twin/commands/heartbeat", Files.readString(Contracts.EXAMPLES.resolve("heartbeat.json")));

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "no es json"})
    void malformedCommandsGetAProblemDetail(String body) throws Exception {
        HttpResponse<String> response = post("/api/twin/commands", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(Contracts.errors(Contracts.openApiComponent("Problem"), Contracts.JSON.readTree(response.body())))
                .isEmpty();
    }

    private BlockingQueue<String> subscribe(String query) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        TextWebSocketHandler collector = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messages.add(message.getPayload());
            }
        };
        sessions.add(new StandardWebSocketClient()
                .execute(collector, "ws://localhost:" + port + "/ws/telemetry" + query)
                .get(TIMEOUT_S, TimeUnit.SECONDS));
        return messages;
    }

    private static JsonNode next(BlockingQueue<String> messages) throws InterruptedException {
        String payload = messages.poll(TIMEOUT_S, TimeUnit.SECONDS);
        assertThat(payload).as("mensaje dentro de %d s", TIMEOUT_S).isNotNull();
        return Contracts.JSON.readTree(payload);
    }

    private static void assertValid(String schema, JsonNode message) {
        assertThat(Contracts.errors(Contracts.schema(schema), message))
                .as("%s cumple %s.schema.json", message, schema)
                .isEmpty();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
