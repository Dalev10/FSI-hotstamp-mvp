package com.simex.twin.application.service;

import com.simex.twin.application.engine.Frames.Envelope;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.machine.MachineSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Arma los frames: sobre con el {@code seq} de cada vista y decide qué ve cada una. Las fallas solo van a la vista
 * groundTruth y no consumen el {@code seq} público, así el agente no ve huecos.
 */
public final class TelemetryAssembler {

    /** Contadores de {@code seq} de una corrida. */
    public interface SeqCounters {
        long nextPublic();

        long nextGroundTruth();
    }

    public TelemetryFrame telemetry(
            String runId,
            Instant runStartedAt,
            SeqCounters seq,
            int speedFactor,
            MachineSnapshot snapshot,
            Instant ts) {
        long simMs = snapshot.simTime().ms();
        return new TelemetryFrame(
                envelope(runId, runStartedAt, seq::nextPublic, ts, simMs),
                envelope(runId, runStartedAt, seq::nextGroundTruth, ts, simMs),
                speedFactor,
                snapshot);
    }

    public EventFrame event(
            String runId, Instant runStartedAt, SeqCounters seq, DomainEvent event, long simTimeMs, Instant ts) {
        Optional<Envelope> publicEnvelope = groundTruthOnly(event)
                ? Optional.empty()
                : Optional.of(envelope(runId, runStartedAt, seq::nextPublic, ts, simTimeMs));
        return new EventFrame(
                publicEnvelope, envelope(runId, runStartedAt, seq::nextGroundTruth, ts, simTimeMs), event);
    }

    /** Hechos que revelarían la falla al agente. */
    public static boolean groundTruthOnly(DomainEvent event) {
        return event instanceof DomainEvent.FaultInjected || event instanceof DomainEvent.FaultCleared;
    }

    private static Envelope envelope(String runId, Instant runStartedAt, LongSupplier seq, Instant ts, long simMs) {
        return new Envelope(seq.getAsLong(), runId, ts, runStartedAt.plusMillis(simMs), simMs);
    }
}
