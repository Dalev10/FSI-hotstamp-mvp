package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * Cada endpoint de {@code openapi.yaml} con sus códigos, y cada cuerpo validado contra su componente. Sin autostart: el
 * gemelo arranca en reposo y solo {@link #theLifecycleOfARunThroughTheApi} inicia corridas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "twin.autostart.scenario=")
class RestApiIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Test
    void theLifecycleOfARunThroughTheApi() throws Exception {
        // Antes de la primera corrida.
        JsonNode idle = ok(get("/api/twin/state"), "State");
        assertThat(idle.get("runId").isNull()).isTrue();
        assertThat(idle.get("simTs").isNull()).isTrue();
        assertThat(idle.has("groundTruth")).isFalse();
        assertThat(ok(get("/api/twin/sim/faults"), "FaultCatalog").get("active"))
                .isEmpty();
        problem(post("/api/twin/sim/faults", "{\"type\":\"COLD_FRONT\"}"), 409);
        problem(delete("/api/twin/sim/faults/" + UUID.randomUUID()), 409);
        HttpResponse<String> noRun = post("/api/twin/commands", command("START", "dashboard", "{}"));
        assertThat(noRun.statusCode()).isEqualTo(409);
        assertThat(valid(noRun, "CommandResult").get("rule").asString()).isEqualTo("STATE_NOT_ALLOWED");

        // Corrida.
        HttpResponse<String> created =
                post("/api/twin/sim/scenario", "{\"scenarioId\":\"nominal\",\"seed\":42,\"speedFactor\":60}");
        assertThat(created.statusCode()).isEqualTo(201);
        valid(created, "RunCreated");

        String body = command("SET_BELT_SPEED", "agent", "{\"pwmDuty\":0.8}");
        HttpResponse<String> first = post("/api/twin/commands", body);
        HttpResponse<String> replay = post("/api/twin/commands", body);
        assertThat(first.statusCode()).isIn(202, 409);
        valid(first, "CommandResult");
        assertThat(first.headers().firstValue("Idempotent-Replay")).isEmpty();
        assertThat(replay.statusCode()).isEqualTo(first.statusCode());
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotent-Replay")).hasValue("true");
        HttpResponse<String> rejected = post("/api/twin/commands", command("RESET", "agent", "{}"));
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(valid(rejected, "CommandResult").get("rule").asString()).isEqualTo("SOURCE_NOT_ALLOWED");

        String heartbeat = "{\"agentId\":\"agente-1\",\"seq\":0,\"sentAt\":\"" + Instant.now() + "\"}";
        assertThat(post("/api/twin/commands/heartbeat", heartbeat).statusCode()).isEqualTo(204);

        // Fallas.
        HttpResponse<String> injected = post(
                "/api/twin/sim/faults", "{\"type\":\"COLD_FRONT\",\"params\":{\"ambientDropC\":5},\"durationS\":300}");
        assertThat(injected.statusCode()).isEqualTo(201);
        String faultId = valid(injected, "FaultCreated").get("faultId").asString();
        JsonNode active = waitForActiveFault(faultId);
        assertThat(active.get("params").get("ambientDropC").asDouble()).isEqualTo(5.0);
        assertThat(active.get("durationS").asDouble()).isEqualTo(300.0);
        assertThat(delete("/api/twin/sim/faults/" + faultId).statusCode()).isEqualTo(204);
        problem(delete("/api/twin/sim/faults/" + faultId), 404);

        JsonNode groundTruth = ok(get("/api/twin/state?groundTruth=true"), "State");
        assertThat(groundTruth.has("groundTruth")).isTrue();
        assertThat(ok(get("/api/twin/state"), "State").has("groundTruth")).isFalse();

        // Control de la simulación.
        assertThat(control("{\"action\":\"SET_SPEED\",\"speedFactor\":5}")
                        .get("speedFactor")
                        .asInt())
                .isEqualTo(5);
        assertThat(control("{\"action\":\"PAUSE\"}").get("status").asString()).isEqualTo("PAUSED");
        assertThat(control("{\"action\":\"RESUME\"}").get("status").asString()).isEqualTo("RUNNING");
        JsonNode stopped = control("{\"action\":\"STOP\"}");
        assertThat(stopped.get("status").asString()).isEqualTo("ENDED");
        assertThat(stopped.get("scenarioId").asString()).isEqualTo("nominal");
        problem(post("/api/twin/sim/faults", "{\"type\":\"SENSOR_NOISE\"}"), 409);
    }

    @Test
    void scenariosAndLimitsMatchTheContract() throws Exception {
        JsonNode scenarios = ok(get("/api/twin/sim/scenarios"), "ScenarioList");
        assertThat(scenarios.findValuesAsString("id")).contains("nominal");

        JsonNode limits = ok(get("/api/twin/limits"), "Limits");
        assertThat(limits).isEqualTo(Contracts.read(Contracts.EXAMPLES.resolve("rest/Limits.json")));
    }

    @ParameterizedTest(name = "{0} {1} → {3}")
    @CsvSource(
            delimiter = '|',
            value = {
                "POST|/api/twin/commands|{no es json|400",
                "POST|/api/twin/commands|{\"type\":\"START\",\"issuedBy\":\"dashboard\"}|400",
                "POST|/api/twin/commands|{\"commandId\":\"no-uuid\",\"type\":\"START\",\"issuedBy\":\"dashboard\"}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"START\",\"issuedBy\":\"scenario\"}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"SET_SETPOINT\",\"issuedBy\":\"agent\"}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"SET_SETPOINT\",\"issuedBy\":\"agent\",\"params\":{\"setpointC\":\"alto\"}}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"START\",\"issuedBy\":\"dashboard\",\"params\":{\"x\":1}}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"VOLAR\",\"issuedBy\":\"dashboard\"}|400",
                "POST|/api/twin/commands|{\"commandId\":\"%s\",\"type\":\"START\",\"issuedBy\":\"dashboard\",\"extra\":1}|400",
                "POST|/api/twin/commands/heartbeat|{\"seq\":0,\"sentAt\":\"2026-11-21T21:00:00Z\"}|400",
                "POST|/api/twin/sim/scenario|{\"scenarioId\":\"nominal\",\"speedFactor\":61}|400",
                "POST|/api/twin/sim/scenario|{\"scenarioId\":\"nominal\"}|400",
                "POST|/api/twin/sim/scenario|{\"scenarioId\":\"inexistente\",\"speedFactor\":1}|404",
                "POST|/api/twin/sim/control|{\"action\":\"PAUSE\",\"speedFactor\":2}|400",
                "POST|/api/twin/sim/control|{\"action\":\"SET_SPEED\"}|400",
                "POST|/api/twin/sim/faults|{\"type\":\"COLD_FRONT\",\"params\":{\"rthFactor\":-1}}|400",
                "POST|/api/twin/sim/faults|{\"type\":\"COLD_FRONT\",\"params\":{\"inventado\":1}}|400",
                "POST|/api/twin/sim/faults|{\"type\":\"COLD_FRONT\",\"durationS\":0}|400",
            })
    void invalidRequestsGetAProblem(String method, String path, String body, int status) throws Exception {
        problem(post(path, body.formatted(UUID.randomUUID())), status);
    }

    @Test
    void theImplementedEndpointsAreExactlyThoseOfTheContract() throws Exception {
        JsonNode docs = Contracts.JSON.readTree(get("/v3/api-docs").body());
        JsonNode contract = Contracts.read(Contracts.DIR.resolve("openapi.yaml"));

        assertThat(operations(docs)).isEqualTo(operations(contract));
    }

    private static Set<String> operations(JsonNode openApi) {
        Set<String> operations = new HashSet<>();
        openApi.get("paths").properties().forEach(path -> {
            if (!path.getKey().startsWith("/actuator")) {
                path.getValue().propertyNames().forEach(method -> operations.add(method + " " + path.getKey()));
            }
        });
        return operations;
    }

    private JsonNode waitForActiveFault(String faultId) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            for (JsonNode fault :
                    ok(get("/api/twin/sim/faults"), "FaultCatalog").get("active")) {
                if (fault.get("faultId").asString().equals(faultId)) {
                    return fault;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("La falla " + faultId + " no apareció en GET /sim/faults");
    }

    private JsonNode control(String body) throws Exception {
        return ok(post("/api/twin/sim/control", body), "SimulationStatus");
    }

    private static String command(String type, String issuedBy, String params) {
        return "{\"commandId\":\"%s\",\"type\":\"%s\",\"issuedBy\":\"%s\",\"params\":%s}"
                .formatted(UUID.randomUUID(), type, issuedBy, params);
    }

    private static JsonNode ok(HttpResponse<String> response, String component) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return valid(response, component);
    }

    private static void problem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(valid(response, "Problem").get("detail").asString()).isNotBlank();
    }

    private static JsonNode valid(HttpResponse<String> response, String component) {
        JsonNode json = Contracts.JSON.readTree(response.body());
        Schema schema = Contracts.openApiComponent(component);
        assertThat(Contracts.errors(schema, json)).as(response.body()).isEmpty();
        return json;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(WAIT).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
