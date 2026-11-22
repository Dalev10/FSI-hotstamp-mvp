package com.simex.twin.adapter.in.rest.stub;

import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stub de los comandos: responde 202 {@code APPLIED} a todo comando con {@code commandId} y {@code type}, sin
 * aplicarlo, y 204 a los latidos. No valida el esquema completo: el validador solo existe en las pruebas.
 */
@RestController
@Profile("stub")
@RequestMapping("/api/twin/commands")
public class StubCommandController {

    private final Instant startedAt = Instant.now();

    @PostMapping
    public ResponseEntity<JsonNode> apply(@RequestBody JsonNode command) {
        if (!command.path("commandId").isString() || !command.path("type").isString()) {
            return ResponseEntity.of(ProblemDetail.forStatusAndDetail(
                            HttpStatus.BAD_REQUEST, "El comando debe incluir commandId y type"))
                    .build();
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("commandId", command.get("commandId"));
        result.put("status", "APPLIED");
        result.put("appliedAtSimMs", Duration.between(startedAt, Instant.now()).toMillis());
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody JsonNode heartbeat) {
        return ResponseEntity.noContent().build();
    }
}
