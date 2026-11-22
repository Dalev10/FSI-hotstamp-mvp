package com.simex.twin.application.engine;

import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.machine.MachineSnapshot;
import java.time.Instant;
import java.util.Optional;

/**
 * Lo que publica el motor: el sobre del contrato con el contenido del dominio adentro. El adaptador lo convierte a JSON
 * y redondea; aquí se deciden el {@code seq} de cada vista y qué ve cada una.
 */
public final class Frames {

    private Frames() {}

    /**
     * Sobre de un mensaje en una vista.
     *
     * @param seq monótono por corrida y por vista, sin huecos dentro de la vista
     * @param ts reloj de pared
     * @param simTs inicio de la corrida (pared) + tiempo simulado
     */
    public record Envelope(long seq, String runId, Instant ts, Instant simTs, long simTimeMs) {}

    /** La telemetría va en las dos vistas, cada una con su {@code seq}. */
    public record TelemetryFrame(
            Envelope publicEnvelope, Envelope groundTruthEnvelope, int speedFactor, MachineSnapshot snapshot) {}

    /**
     * @param publicEnvelope vacío si el evento es solo de la vista groundTruth (FAULT_INJECTED, FAULT_CLEARED)
     */
    public record EventFrame(Optional<Envelope> publicEnvelope, Envelope groundTruthEnvelope, DomainEvent event) {}
}
