package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.control.ControlEvent;
import com.simex.twin.domain.control.alarm.AlarmRegistry;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Estado compartido por los handlers de un mismo {@link Sequencer}. Lo muta solo el hilo del motor. */
final class SequenceContext {

    final SequenceParameters parameters;
    final AlarmRegistry alarms;
    SequenceInput in;
    List<ControlEvent> events = new ArrayList<>();
    SimTime now = SimTime.ZERO;
    SimTime stateEnteredAt = SimTime.ZERO;
    SequenceOutput output = SequenceOutput.ALL_OFF;
    boolean stopPending;
    /** Flanco del inductivo retenido hasta que TRANSPORTE pueda atenderlo. */
    boolean inductivePending;
    /** Cuándo se detectó la pieza en la estación; vacío si no hay ninguna pendiente. */
    Optional<SimTime> stationDetectedAt = Optional.empty();

    OptionalLong lastStampStrokeMs = OptionalLong.empty();

    SequenceContext(SequenceParameters parameters, AlarmRegistry alarms, SequenceInput initial) {
        this.parameters = parameters;
        this.alarms = alarms;
        this.in = initial;
    }

    SimDuration timeInState() {
        return now.minus(stateEnteredAt);
    }

    boolean beltStopped() {
        return in.inputs().beltSpeedMps() == 0;
    }
}
