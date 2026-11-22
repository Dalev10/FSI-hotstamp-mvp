package com.simex.twin.application.engine;

import java.util.Optional;

/** Estado de la simulación (respuesta de {@code POST /sim/control}). */
public record SimulationStatus(
        Optional<String> runId, Optional<String> scenarioId, Status status, int speedFactor, long simTimeMs) {

    public enum Status {
        IDLE,
        RUNNING,
        PAUSED,
        ENDED
    }
}
