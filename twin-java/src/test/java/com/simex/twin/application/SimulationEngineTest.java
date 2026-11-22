package com.simex.twin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.engine.EngineRequest.CommandOutcome;
import com.simex.twin.application.engine.Frames.Envelope;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.application.engine.IdempotencyStore;
import com.simex.twin.application.engine.SimulationStatus;
import com.simex.twin.application.port.in.SimulationControlUseCase.UnknownScenarioException;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.command.Rule;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.shared.SimTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Criterios de cierre de la T8 con el motor síncrono y el reloj falso (sin hilos ni esperas). */
class SimulationEngineTest {

    private final EngineFixture f = new EngineFixture();

    @Test
    void beforeAnyRunTheViewShowsAnIdleMachine() {
        assertThat(f.engine.view().status().status()).isEqualTo(SimulationStatus.Status.IDLE);
        assertThat(f.engine.view().latest()).isEmpty();
        assertThat(f.engine.view().snapshot().control().state()).isEqualTo(MachineState.OFF);
    }

    @Test
    void atTimesTenTenWallCyclesAdvanceTenSimulatedSeconds() {
        f.service.startRun("nominal", Optional.empty(), 10, Optional.empty());
        f.cycles(10); // 1 s de pared

        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(10_000);
        assertThat(f.publisher.telemetry).hasSize(10); // una por segundo simulado
        assertThat(f.publisher.telemetry.getLast().publicEnvelope().simTimeMs()).isEqualTo(10_000);
    }

    @Test
    void seqIsConsecutiveInEachViewAndFaultsStayOutOfThePublicOne() {
        f.service.startRun("nominal", Optional.empty(), 10, Optional.empty());
        f.cycles(5);
        f.service.inject(FaultType.SENSOR_NOISE, Map.of(), Optional.of(2.0));
        f.cycles(10);

        List<EventFrame> faults = f.publisher.events.stream()
                .filter(e ->
                        e.event() instanceof DomainEvent.FaultInjected || e.event() instanceof DomainEvent.FaultCleared)
                .toList();
        assertThat(faults)
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.publicEnvelope()).isEmpty());
        assertThat(publicSeqs()).isEqualTo(consecutive(publicSeqs().size()));
        assertThat(groundTruthSeqs()).isEqualTo(consecutive(groundTruthSeqs().size()));
        assertThat(groundTruthSeqs()).hasSize(publicSeqs().size() + 2);
    }

    @Test
    void theRunStartsWithRunStartedCarryingTheInitialControlParameters() {
        f.service.startRun("nominal", Optional.of(7L), 5, Optional.empty());
        f.cycles(1);

        EventFrame first = f.publisher.events.getFirst();
        assertThat(first.groundTruthEnvelope().seq()).isEqualTo(1);
        assertThat(first.groundTruthEnvelope().runId()).isEqualTo("run-1");
        assertThat(first.event())
                .isEqualTo(new DomainEvent.RunStarted(
                        "nominal",
                        7,
                        com.simex.twin.domain.control.thermal.ControlMode.PID,
                        5,
                        new DomainEvent.RunParameters(62.5, 10.0, 0.1, 0.0, 0.7)));
        assertThat(first.groundTruthEnvelope().simTs()).isEqualTo(f.clock.now().minusMillis(100));
    }

    @Test
    void pauseResumeAndSetSpeedWork() {
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.cycles(10);
        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(1_000);

        f.service.control(Action.PAUSE, OptionalInt.empty());
        f.cycles(10);
        assertThat(f.engine.view().status().status()).isEqualTo(SimulationStatus.Status.PAUSED);
        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(1_000);

        f.service.control(Action.RESUME, OptionalInt.empty());
        f.service.control(Action.SET_SPEED, OptionalInt.of(30));
        f.cycles(10);
        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(1_000 + 30_000);
        assertThat(f.engine.view().status().speedFactor()).isEqualTo(30);
    }

    @Test
    void aCommandIsAppliedWithinOneWallCycleEvenWhilePaused() {
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.cycles(1);
        f.service.control(Action.PAUSE, OptionalInt.empty());
        CompletableFuture<CommandOutcome> result =
                f.service.apply(command("c-1", new MachineCommand.SetBeltSpeed(0.8)));
        assertThat(result).isNotDone();

        f.cycles(1);

        assertThat(result)
                .isCompletedWithValueMatching(o -> o.result() instanceof CommandResult.Applied && !o.replay());
    }

    @Test
    void aRepeatedCommandIdIsAppliedOnlyOnce() {
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.cycles(1);
        CompletableFuture<CommandOutcome> first = f.service.apply(command("dup", new MachineCommand.SetBeltSpeed(0.8)));
        CompletableFuture<CommandOutcome> second =
                f.service.apply(command("dup", new MachineCommand.SetBeltSpeed(0.9)));
        f.cycles(2);

        assertThat(first.join().replay()).isFalse();
        assertThat(second.join().replay()).isTrue();
        assertThat(second.join().result()).isEqualTo(first.join().result());
        assertThat(f.publisher.eventsOf(DomainEvent.CommandApplied.class))
                .filteredOn(e -> e.commandId().equals("dup"))
                .hasSize(1);
        assertThat(f.engine.view().snapshot().control().beltPwm()).isEqualTo(0.8);
    }

    @Test
    void commandsWithoutAnActiveRunAreRejected() {
        CompletableFuture<CommandOutcome> result = f.service.apply(command("c-1", new MachineCommand.Start()));
        f.cycles(1);

        assertThat(result.join().result())
                .isInstanceOfSatisfying(
                        CommandResult.Rejected.class, r -> assertThat(r.rule()).isEqualTo(Rule.STATE_NOT_ALLOWED));
    }

    @Test
    void aRunEndsWhenCompletedAndANewRunReplacesTheCurrentOne() {
        f.service.startRun("nominal", Optional.empty(), 10, Optional.empty());
        f.cycles(1);
        f.service.startRun("frente-frio", Optional.empty(), 60, Optional.empty());
        f.cycles(600); // 3600 s simulados: a ×60 cada ciclo de 100 ms avanza 6 s

        assertThat(f.publisher.eventsOf(DomainEvent.RunEnded.class))
                .extracting(DomainEvent.RunEnded::reason)
                .containsExactly(DomainEvent.RunEndReason.REPLACED, DomainEvent.RunEndReason.COMPLETED);
        assertThat(f.engine.view().status().status()).isEqualTo(SimulationStatus.Status.ENDED);
        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(3_600_000);
        assertThat(f.engine.view().status().runId()).hasValue("run-2");
    }

    @Test
    void stopEndsTheRunAndHeartbeatsReachTheWatchdog() {
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.service.heartbeat();
        f.cycles(2);
        assertThat(f.engine.view().snapshot().control().supervisor()).isEqualTo(Supervisor.AGENT);

        f.service.control(Action.STOP, OptionalInt.empty());
        f.cycles(1);
        assertThat(f.publisher.eventsOf(DomainEvent.RunEnded.class))
                .extracting(DomainEvent.RunEnded::reason)
                .containsExactly(DomainEvent.RunEndReason.STOPPED);
        assertThat(f.engine.view().status().status()).isEqualTo(SimulationStatus.Status.ENDED);
    }

    @Test
    void faultsCanBeInjectedAndRemovedOnlyDuringARun() {
        CompletableFuture<Optional<String>> early =
                f.service.inject(FaultType.SENSOR_NOISE, Map.of(), Optional.empty());
        f.cycles(1);
        assertThat(early.join()).isEmpty();

        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        CompletableFuture<Optional<String>> id = f.service.inject(FaultType.SENSOR_NOISE, Map.of(), Optional.empty());
        f.cycles(1);
        CompletableFuture<Boolean> removed = f.service.remove(id.join().orElseThrow());
        CompletableFuture<Boolean> missing = f.service.remove("inexistente");
        f.cycles(1);

        assertThat(removed.join()).isTrue();
        assertThat(missing.join()).isFalse();
    }

    @Test
    void theServiceValidatesBeforeQueueing() {
        assertThatThrownBy(() -> f.service.startRun("inexistente", Optional.empty(), 1, Optional.empty()))
                .isInstanceOf(UnknownScenarioException.class)
                .hasMessageContaining("inexistente");
        assertThatThrownBy(() -> f.service.startRun("nominal", Optional.empty(), 61, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.service.control(Action.SET_SPEED, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.service.inject(FaultType.SENSOR_NOISE, Map.of("x", 1.0), Optional.empty()))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.service.scenarios()).hasSize(9);
        assertThat(f.service.controlParameters().commandLimits().beltPwmMaxStep())
                .isEqualTo(0.2);
    }

    @Test
    void theFaultCatalogListsEveryTypeWithItsDefaults() {
        assertThat(f.service.catalog()).hasSize(FaultType.values().length);
        assertThat(f.service.catalog())
                .filteredOn(e -> e.type() == FaultType.PNEUMATIC_LEAK)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.params()).contains("cylinder", "strokeFactor");
                    assertThat(e.defaults()).containsOnlyKeys("strokeFactor");
                });
    }

    @Test
    void maxModeRunsWithoutSleeping() {
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.engine.runMax(18_000);

        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(1_800_000);
        assertThat(f.engine.view().latest())
                .map(TelemetryFrame::snapshot)
                .get()
                .satisfies(s -> assertThat(s.simTime()).isEqualTo(new SimTime(1_800_000)));
    }

    @Test
    void theIdempotencyStoreForgetsTheLeastRecentlyUsed() {
        IdempotencyStore store = new IdempotencyStore(2);
        store.remember(new CommandResult.Applied("a", SimTime.ZERO));
        store.remember(new CommandResult.Applied("b", SimTime.ZERO));
        store.find("a");
        store.remember(new CommandResult.Applied("c", SimTime.ZERO));

        assertThat(store.find("a")).isPresent();
        assertThat(store.find("b")).isEmpty();
        assertThat(store.find("c")).isPresent();
    }

    private List<Long> publicSeqs() {
        return Stream.concat(
                        f.publisher.events.stream().flatMap(e -> e.publicEnvelope().stream()),
                        f.publisher.telemetry.stream().map(TelemetryFrame::publicEnvelope))
                .map(Envelope::seq)
                .sorted()
                .toList();
    }

    private List<Long> groundTruthSeqs() {
        return Stream.concat(
                        f.publisher.events.stream().map(EventFrame::groundTruthEnvelope),
                        f.publisher.telemetry.stream().map(TelemetryFrame::groundTruthEnvelope))
                .map(Envelope::seq)
                .sorted()
                .toList();
    }

    private static List<Long> consecutive(int count) {
        return Stream.iterate(1L, n -> n + 1).limit(count).toList();
    }

    private static IssuedCommand command(String id, MachineCommand command) {
        return new IssuedCommand(id, IssuedCommand.Issuer.DASHBOARD, Optional.empty(), command);
    }
}
