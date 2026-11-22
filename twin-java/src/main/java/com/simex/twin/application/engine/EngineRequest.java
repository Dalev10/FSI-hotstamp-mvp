package com.simex.twin.application.engine;

import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.Fault;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.shared.SimDuration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Pedidos que llegan de otros hilos y aplica el hilo del motor (patrón Active Object): así el dominio tiene un solo
 * escritor. Cada pedido trae su futuro para devolver el resultado.
 */
public sealed interface EngineRequest {

    /** El resultado de un comando y si es la repetición de un {@code commandId} ya procesado. */
    record CommandOutcome(CommandResult result, boolean replay) {}

    record ApplyCommand(IssuedCommand command, CompletableFuture<CommandOutcome> result) implements EngineRequest {}

    record Heartbeat() implements EngineRequest {}

    record StartRun(
            Scenario scenario,
            Optional<Long> seed,
            int speedFactor,
            Optional<ControlMode> controlMode,
            CompletableFuture<String> runId)
            implements EngineRequest {}

    enum Action {
        PAUSE,
        RESUME,
        STOP,
        SET_SPEED
    }

    record Control(Action action, OptionalInt speedFactor, CompletableFuture<SimulationStatus> status)
            implements EngineRequest {}

    /** Vacío si no hay corrida activa. */
    record InjectFault(Fault fault, Optional<SimDuration> duration, CompletableFuture<Optional<String>> faultId)
            implements EngineRequest {}

    record RemoveFault(String faultId, CompletableFuture<Boolean> removed) implements EngineRequest {}
}
