package com.simex.twin.adapter.contract;

import com.simex.twin.adapter.contract.ContractMessages.EventMessage;
import com.simex.twin.adapter.contract.ContractMessages.HelloMessage;
import com.simex.twin.adapter.contract.ContractMessages.SnapshotBody;
import com.simex.twin.adapter.contract.ContractMessages.StateMessage;
import com.simex.twin.adapter.contract.ContractMessages.TelemetryMessage;
import com.simex.twin.application.engine.Frames.Envelope;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.application.engine.SimulationEngine.EngineView;
import com.simex.twin.domain.control.ControlStatus;
import com.simex.twin.domain.control.alarm.ActiveAlarm;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.machine.MachineSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Del dominio al JSON del contrato. Redondea (temperaturas a 0,01; ciclos útiles a 0,001; energía a 0,1) y decide la
 * vista: {@code groundTruth} y el {@code scenarioId} de {@code RUN_STARTED} solo en la vista groundTruth.
 */
public final class ContractMapper {

    public static final String SCHEMA_VERSION = "1.0";
    private static final int TEMP_DECIMALS = 2;
    private static final int DUTY_DECIMALS = 3;
    private static final int ENERGY_DECIMALS = 1;
    private static final int SPEED_DECIMALS = 4;

    public TelemetryMessage telemetry(TelemetryFrame frame, boolean groundTruth) {
        Envelope e = groundTruth ? frame.groundTruthEnvelope() : frame.publicEnvelope();
        return new TelemetryMessage(
                "telemetry",
                SCHEMA_VERSION,
                e.seq(),
                e.runId(),
                e.ts().toString(),
                e.simTs().toString(),
                e.simTimeMs(),
                body(frame.snapshot(), frame.speedFactor(), groundTruth));
    }

    /** Vacío si el evento no pertenece a la vista pública. */
    public Optional<EventMessage> event(EventFrame frame, boolean groundTruth) {
        Optional<Envelope> envelope = groundTruth ? Optional.of(frame.groundTruthEnvelope()) : frame.publicEnvelope();
        return envelope.map(e -> {
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            String type = data(frame.event(), groundTruth, data);
            return new EventMessage(
                    "event",
                    SCHEMA_VERSION,
                    e.seq(),
                    e.runId(),
                    e.ts().toString(),
                    e.simTs().toString(),
                    e.simTimeMs(),
                    type,
                    data);
        });
    }

    /** El estado actual con el sobre de la última telemetría de su vista ({@code seq} 0 si todavía no hubo). */
    public StateMessage state(EngineView view, boolean groundTruth, Instant now) {
        long seq = view.latest()
                .map(f -> (groundTruth ? f.groundTruthEnvelope() : f.publicEnvelope()).seq())
                .orElse(0L);
        long simMs = view.snapshot().simTime().ms();
        return new StateMessage(
                SCHEMA_VERSION,
                seq,
                view.status().runId().orElse(null),
                now.toString(),
                view.runStartedAt()
                        .map(start -> start.plusMillis(simMs).toString())
                        .orElse(null),
                simMs,
                body(view.snapshot(), view.status().speedFactor(), groundTruth));
    }

    /**
     * @param lastSeq último {@code seq} enviado en esta vista ({@code hello} no lo consume)
     */
    public HelloMessage hello(EngineView view, long lastSeq, boolean groundTruth, Instant now) {
        StateMessage state = state(view, groundTruth, now);
        return new HelloMessage(
                "hello",
                SCHEMA_VERSION,
                lastSeq,
                state.runId(),
                state.ts(),
                state.simTs(),
                state.simTimeMs(),
                state.body());
    }

    public SnapshotBody body(MachineSnapshot s, int speedFactor, boolean groundTruth) {
        ControlStatus c = s.control();
        return new SnapshotBody(
                speedFactor,
                c.state().name(),
                new ContractMessages.Thermal(
                        round(c.measuredTempC(), TEMP_DECIMALS),
                        round(c.setpointC(), TEMP_DECIMALS),
                        c.band().name(),
                        round(c.heaterDuty(), DUTY_DECIMALS),
                        c.outputs().ssr(),
                        round(c.tcMillivolts(), DUTY_DECIMALS),
                        round(c.inputs().ambientC(), TEMP_DECIMALS)),
                new ContractMessages.Conveyor(
                        c.outputs().motorPwm().value() > 0,
                        round(c.beltPwm(), DUTY_DECIMALS),
                        round(c.inputs().beltSpeedMps(), SPEED_DECIMALS),
                        s.queueLength()),
                new ContractMessages.Sensors(
                        c.inputs().inductive() ? 1 : 0, c.inputs().photoelectric() ? 1 : 0),
                new ContractMessages.Valves(
                        c.inputs().discardPosition().name(),
                        c.inputs().stampPosition().name(),
                        c.lastStampStrokeMs().isPresent()
                                ? c.lastStampStrokeMs().getAsLong()
                                : null),
                new ContractMessages.Counters(
                        s.counters().metalDiscarded(),
                        s.counters().plasticStamped(),
                        s.counters().metalEscaped(),
                        s.counters().piecesArrived()),
                new ContractMessages.Energy(round(s.heaterWh(), ENERGY_DECIMALS), round(s.motorWh(), ENERGY_DECIMALS)),
                new ContractMessages.Control(
                        c.mode().name(),
                        c.kp(),
                        c.ki(),
                        c.kd(),
                        c.supervisor().name(),
                        c.agentHeartbeatAgeMs().isPresent()
                                ? c.agentHeartbeatAgeMs().getAsLong()
                                : null),
                new ContractMessages.Safety(
                        c.estop(),
                        c.interlock().map(Enum::name).orElse(null),
                        c.activeAlarms().stream().map(ContractMapper::alarm).toList()),
                groundTruth ? groundTruth(s) : null);
    }

    private static ContractMessages.GroundTruth groundTruth(MachineSnapshot s) {
        MachineSnapshot.GroundTruth g = s.groundTruth();
        return new ContractMessages.GroundTruth(
                round(g.platenTempC(), TEMP_DECIMALS),
                round(g.ambientC(), TEMP_DECIMALS),
                round(g.heaterEfficiency(), DUTY_DECIMALS),
                g.activeFaults().stream()
                        .map(f -> new ContractMessages.ActiveFault(
                                f.faultId(), f.type().name(), f.since().ms()))
                        .toList(),
                g.stampedOutOfWindow());
    }

    private static ContractMessages.Alarm alarm(ActiveAlarm a) {
        return new ContractMessages.Alarm(
                a.code().name(),
                a.code().severity().name(),
                a.latched(),
                a.acknowledged(),
                a.raisedAt().ms(),
                a.detail().orElse(null));
    }

    /** Llena {@code data} y devuelve el {@code eventType}. */
    private static String data(DomainEvent event, boolean groundTruth, Map<String, @Nullable Object> data) {
        switch (event) {
            case DomainEvent.PieceDiscarded e -> {
                data.put("pieceId", e.pieceId());
                return "PIECE_DISCARDED";
            }
            case DomainEvent.PieceStamped e -> {
                data.put("pieceId", e.pieceId());
                data.put("tempC", round(e.tempC(), TEMP_DECIMALS));
                data.put("waitMs", e.waitMs());
                data.put("stampStrokeMs", e.stampStrokeMs());
                return "PIECE_STAMPED";
            }
            case DomainEvent.PieceExited e -> {
                data.put("pieceId", e.pieceId());
                data.put("result", e.result().name());
                return "PIECE_EXITED";
            }
            case DomainEvent.StateChanged e -> {
                data.put("from", e.from().name());
                data.put("to", e.to().name());
                return "STATE_CHANGED";
            }
            case DomainEvent.AlarmRaised e -> {
                data.put("code", e.code().name());
                data.put("severity", e.severity().name());
                e.detail().ifPresent(d -> data.put("detail", d));
                return "ALARM_RAISED";
            }
            case DomainEvent.AlarmCleared e -> {
                data.put("code", e.code().name());
                data.put("severity", e.severity().name());
                return "ALARM_CLEARED";
            }
            case DomainEvent.CommandApplied e -> {
                command(data, e.commandId(), e.type().name(), e.issuedBy());
                data.put("rule", null);
                return "COMMAND_APPLIED";
            }
            case DomainEvent.CommandRejected e -> {
                command(data, e.commandId(), e.type().name(), e.issuedBy());
                data.put("rule", e.rule().name());
                data.put("detail", e.detail());
                return "COMMAND_REJECTED";
            }
            case DomainEvent.FaultInjected e -> {
                data.put("faultId", e.faultId());
                data.put("type", e.type().name());
                data.put("params", e.params());
                data.put("durationS", e.durationS().orElse(null));
                return "FAULT_INJECTED";
            }
            case DomainEvent.FaultCleared e -> {
                data.put("faultId", e.faultId());
                data.put("type", e.type().name());
                return "FAULT_CLEARED";
            }
            case DomainEvent.SupervisorChanged e -> {
                data.put("from", e.from().name());
                data.put("to", e.to().name());
                return "SUPERVISOR_CHANGED";
            }
            case DomainEvent.RunStarted e -> {
                if (groundTruth) {
                    data.put("scenarioId", e.scenarioId()); // el nombre del escenario revela la falla
                }
                data.put("seed", e.seed());
                data.put("controlMode", e.controlMode().name());
                data.put("speedFactor", e.speedFactor());
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("setpointC", e.parameters().setpointC());
                parameters.put("kp", e.parameters().kp());
                parameters.put("ki", e.parameters().ki());
                parameters.put("kd", e.parameters().kd());
                parameters.put("beltPwm", e.parameters().beltPwm());
                data.put("parameters", parameters);
                return "RUN_STARTED";
            }
            case DomainEvent.RunEnded e -> {
                data.put("reason", e.reason().name());
                return "RUN_ENDED";
            }
        }
    }

    private static void command(Map<String, @Nullable Object> data, String id, String type, Issuer issuer) {
        data.put("commandId", id);
        data.put("type", type);
        data.put("issuedBy", issuer.name().toLowerCase(Locale.ROOT));
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}
