package com.simex.twin.adapter.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Mensajes con la forma exacta de {@code contracts/schemas} (capa anticorrupción): el dominio no conoce este JSON.
 * Los campos {@code @Nullable} sin {@code @JsonInclude} se serializan como {@code null}, como pide el contrato.
 */
public final class ContractMessages {

    private ContractMessages() {}

    public record Thermal(
            double tempC,
            double setpointC,
            String band,
            double heaterDuty,
            boolean ssr,
            double tcMillivolts,
            double ambientC) {}

    public record Conveyor(boolean running, double pwmDuty, double speedMps, int queueLength) {}

    public record Sensors(int inductive, int photoelectric) {}

    public record Valves(
            String discard, String stamp, @Nullable Long stampStrokeMs) {}

    public record Counters(long metalDiscarded, long plasticStamped, long metalEscaped, long piecesArrived) {}

    public record Energy(double heaterWh, double motorWh) {}

    public record Control(
            String mode,
            double kp,
            double ki,
            double kd,
            String supervisor,
            @Nullable Long agentHeartbeatAgeMs) {}

    public record Alarm(
            String code,
            String severity,
            boolean latched,
            boolean acknowledged,
            long raisedAtSimMs,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String detail) {}

    public record Safety(boolean estop, @Nullable String interlock, List<Alarm> activeAlarms) {}

    public record ActiveFault(String faultId, String type, long sinceSimMs) {}

    public record GroundTruth(
            double platenTempC,
            double ambientC,
            double heaterEfficiency,
            List<ActiveFault> activeFaults,
            long stampedOutOfWindow) {}

    public record SnapshotBody(
            int speedFactor,
            String state,
            Thermal thermal,
            Conveyor conveyor,
            Sensors sensors,
            Valves valves,
            Counters counters,
            Energy energy,
            Control control,
            Safety safety,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            GroundTruth groundTruth) {}

    public record TelemetryMessage(
            String type,
            String schemaVersion,
            long seq,
            String runId,
            String ts,
            String simTs,
            long simTimeMs,
            @JsonUnwrapped SnapshotBody body) {}

    /** {@code GET /api/twin/state}: la telemetría sin {@code type}. */
    public record StateMessage(
            String schemaVersion,
            long seq,
            @Nullable String runId,
            String ts,
            @Nullable String simTs,
            long simTimeMs,
            @JsonUnwrapped SnapshotBody body) {}

    public record HelloMessage(
            String type,
            String schemaVersion,
            long seq,
            @Nullable String runId,
            String ts,
            @Nullable String simTs,
            long simTimeMs,
            SnapshotBody snapshot) {}

    public record EventMessage(
            String type,
            String schemaVersion,
            long seq,
            String runId,
            String ts,
            String simTs,
            long simTimeMs,
            String eventType,
            Map<String, @Nullable Object> data) {}
}
