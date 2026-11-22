package com.simex.twin.application.port.in;

import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.engine.SimulationStatus;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.scenario.Scenario;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** Corridas y control de la simulación. */
public interface SimulationControlUseCase {

    List<Scenario> scenarios();

    /**
     * Inicia una corrida y reemplaza la actual; se completa con el {@code runId}.
     *
     * @throws UnknownScenarioException si el escenario no existe
     * @throws IllegalArgumentException si {@code speedFactor} está fuera de rango
     */
    CompletableFuture<String> startRun(
            String scenarioId, Optional<Long> seed, int speedFactor, Optional<ControlMode> controlMode);

    /** @throws IllegalArgumentException si {@code SET_SPEED} no trae un {@code speedFactor} válido */
    CompletableFuture<SimulationStatus> control(Action action, OptionalInt speedFactor);

    final class UnknownScenarioException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public UnknownScenarioException(String scenarioId) {
            super("No existe el escenario '" + scenarioId + "'");
        }
    }
}
