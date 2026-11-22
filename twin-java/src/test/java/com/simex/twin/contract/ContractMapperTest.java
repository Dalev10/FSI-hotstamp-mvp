package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.simex.twin.adapter.contract.ContractMapper;
import com.simex.twin.application.EngineFixture;
import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.fault.FaultType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Todo lo que produce el mapper, en las dos vistas, cumple los esquemas del contrato. */
class ContractMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-21T21:00:00Z");

    private final ContractMapper mapper = new ContractMapper();
    private final EngineFixture f = new EngineFixture();

    @Test
    void beforeTheFirstRunHelloAndStateHaveNullRunAndSimTs() {
        JsonNode hello = json(mapper.hello(f.engine.view(), 0, false, NOW));
        JsonNode state = json(mapper.state(f.engine.view(), true, NOW));

        assertValid(Contracts.schema("hello"), hello);
        assertValid(Contracts.openApiComponent("State"), state);
        assertThat(hello.get("runId").isNull()).isTrue();
        assertThat(hello.get("simTs").isNull()).isTrue();
        assertThat(hello.path("snapshot").has("groundTruth")).isFalse();
        assertThat(state.has("groundTruth")).isTrue();
    }

    @Test
    void aRunWithFaultsCommandsAndAlarmsMapsToValidMessagesInBothViews() {
        runWithEverything();

        Set<String> publicTypes = new HashSet<>();
        Set<String> groundTruthTypes = new HashSet<>();
        for (EventFrame frame : f.publisher.events) {
            JsonNode gt = json(mapper.event(frame, true).orElseThrow());
            assertValid(Contracts.schema("event"), gt);
            groundTruthTypes.add(gt.get("eventType").asString());
            mapper.event(frame, false).map(this::json).ifPresent(pub -> {
                assertValid(Contracts.schema("event"), pub);
                publicTypes.add(pub.get("eventType").asString());
                if (pub.get("eventType").asString().equals("RUN_STARTED")) {
                    assertThat(pub.path("data").has("scenarioId")).isFalse();
                    assertThat(gt.path("data").get("scenarioId").asString()).isEqualTo("nominal");
                }
                if (pub.get("eventType").asString().startsWith("COMMAND_")) {
                    assertThat(pub.path("data").get("issuedBy").asString()).isIn("agent", "dashboard", "scenario");
                }
            });
        }
        for (TelemetryFrame frame : f.publisher.telemetry) {
            JsonNode pub = json(mapper.telemetry(frame, false));
            JsonNode gt = json(mapper.telemetry(frame, true));
            assertValid(Contracts.schema("telemetry"), pub);
            assertValid(Contracts.schema("telemetry"), gt);
            assertThat(pub.has("groundTruth")).isFalse();
            assertThat(gt.has("groundTruth")).isTrue();
        }
        assertValid(Contracts.schema("hello"), json(mapper.hello(f.engine.view(), 7, true, NOW)));
        assertValid(Contracts.openApiComponent("State"), json(mapper.state(f.engine.view(), false, NOW)));

        assertThat(groundTruthTypes)
                .contains(
                        "RUN_STARTED",
                        "RUN_ENDED",
                        "STATE_CHANGED",
                        "PIECE_DISCARDED",
                        "PIECE_STAMPED",
                        "PIECE_EXITED",
                        "ALARM_RAISED",
                        "ALARM_CLEARED",
                        "COMMAND_APPLIED",
                        "COMMAND_REJECTED",
                        "SUPERVISOR_CHANGED",
                        "FAULT_INJECTED",
                        "FAULT_CLEARED");
        assertThat(publicTypes)
                .doesNotContain("FAULT_INJECTED", "FAULT_CLEARED")
                .contains("RUN_STARTED");
    }

    @Test
    void valuesAreRoundedToTheContractPrecision() {
        f.service.startRun("nominal", Optional.of(42L), 60, Optional.empty());
        f.cycles(30);
        JsonNode thermal =
                json(mapper.telemetry(f.publisher.telemetry.getLast(), true)).get("thermal");

        assertThat(decimals(thermal.get("tempC").asDouble())).isLessThanOrEqualTo(2);
        assertThat(decimals(thermal.get("heaterDuty").asDouble())).isLessThanOrEqualTo(3);
    }

    private void runWithEverything() {
        f.service.startRun("nominal", Optional.of(42L), 60, Optional.empty());
        f.cycles(100); // 600 s: calienta, estampa y descarta
        f.service.apply(command(Issuer.AGENT, new MachineCommand.SetSetpoint(64)));
        f.service.apply(command(Issuer.AGENT, new MachineCommand.Reset())); // el agente no puede: rechazado
        f.service.inject(FaultType.THERMOCOUPLE_OPEN, Map.of(), Optional.empty());
        f.cycles(2);
        String faultId = f.engine
                .view()
                .snapshot()
                .groundTruth()
                .activeFaults()
                .getFirst()
                .faultId();
        f.service.remove(faultId);
        f.cycles(2);
        f.service.apply(command(Issuer.DASHBOARD, new MachineCommand.AckAlarm(Optional.empty())));
        f.service.apply(command(Issuer.DASHBOARD, new MachineCommand.Reset()));
        f.service.control(Action.SET_SPEED, OptionalInt.of(1));
        f.service.heartbeat();
        f.cycles(120); // 12 s de pared sin latidos: el watchdog pasa a modo seguro
        f.service.startRun("nominal", Optional.of(42L), 1, Optional.empty()); // RUN_ENDED REPLACED
        f.cycles(1);
    }

    private static IssuedCommand command(Issuer issuer, MachineCommand command) {
        return new IssuedCommand(UUID.randomUUID().toString(), issuer, Optional.empty(), command);
    }

    private JsonNode json(Object message) {
        return Contracts.JSON.valueToTree(message);
    }

    private static int decimals(double value) {
        String text = Double.toString(value);
        return text.contains("E") ? 0 : text.length() - text.indexOf('.') - 1;
    }

    private static void assertValid(Schema schema, JsonNode node) {
        assertThat(Contracts.errors(schema, node)).as(node.toString()).isEmpty();
    }
}
