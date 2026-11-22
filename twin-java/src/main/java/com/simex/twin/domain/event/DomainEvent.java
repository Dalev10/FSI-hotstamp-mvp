package com.simex.twin.domain.event;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.alarm.Severity;
import com.simex.twin.domain.control.command.CommandType;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.Rule;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.plant.conveyor.ExitResult;
import java.util.Map;
import java.util.Optional;

/**
 * Hechos del dominio que se publican al momento (sección 4.12). Es sealed porque forma parte del contrato versionado.
 * Los arma la máquina a partir de los hechos del control y de la planta.
 */
public sealed interface DomainEvent {

    record PieceDiscarded(String pieceId) implements DomainEvent {}

    /**
     * @param tempC temperatura medida al iniciar el estampado
     * @param waitMs tiempo entre la detección en la estación y el inicio del estampado
     */
    record PieceStamped(String pieceId, double tempC, long waitMs, long stampStrokeMs) implements DomainEvent {}

    record PieceExited(String pieceId, ExitResult result) implements DomainEvent {}

    record StateChanged(MachineState from, MachineState to) implements DomainEvent {}

    record AlarmRaised(AlarmCode code, Severity severity, Optional<String> detail) implements DomainEvent {}

    record AlarmCleared(AlarmCode code, Severity severity) implements DomainEvent {}

    record CommandApplied(String commandId, CommandType type, Issuer issuedBy) implements DomainEvent {}

    record CommandRejected(String commandId, CommandType type, Issuer issuedBy, Rule rule, String detail)
            implements DomainEvent {}

    record SupervisorChanged(Supervisor from, Supervisor to) implements DomainEvent {}

    /**
     * Solo para la vista groundTruth.
     *
     * @param params parámetros efectivos, con los valores por defecto aplicados
     * @param durationS vacío: dura hasta que se retira
     */
    record FaultInjected(String faultId, FaultType type, Map<String, Object> params, Optional<Double> durationS)
            implements DomainEvent {}

    /** Solo para la vista groundTruth. */
    record FaultCleared(String faultId, FaultType type) implements DomainEvent {}

    /**
     * Inicio de una corrida. En la vista pública el adaptador omite {@code scenarioId}: el nombre revela la falla.
     *
     * @param parameters parámetros de control iniciales (nada del modelo térmico)
     */
    record RunStarted(String scenarioId, long seed, ControlMode controlMode, int speedFactor, RunParameters parameters)
            implements DomainEvent {}

    record RunParameters(double setpointC, double kp, double ki, double kd, double beltPwm) {}

    record RunEnded(RunEndReason reason) implements DomainEvent {}

    enum RunEndReason {
        COMPLETED,
        STOPPED,
        REPLACED
    }
}
