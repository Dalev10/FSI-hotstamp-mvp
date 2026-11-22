package com.simex.twin.domain.control;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.sequence.MachineState;
import java.util.Optional;

/**
 * Hechos del control en un scan. La máquina los traduce a eventos de dominio: el control no depende del paquete de
 * eventos, así no hay ciclos (regla 5 de ArchUnit).
 */
public sealed interface ControlEvent {

    record StateChanged(MachineState from, MachineState to) implements ControlEvent {}

    record AlarmRaised(AlarmCode code, Optional<String> detail) implements ControlEvent {}

    record AlarmCleared(AlarmCode code) implements ControlEvent {}

    record SupervisorChanged(Supervisor from, Supervisor to) implements ControlEvent {}

    /**
     * Terminó un ciclo de estampado.
     *
     * @param tempC temperatura medida al iniciar la extensión
     * @param waitMs tiempo entre la detección en la estación y el inicio del estampado
     * @param strokeMs duración medida de la extensión del cilindro
     */
    record StampCompleted(double tempC, long waitMs, long strokeMs) implements ControlEvent {}
}
