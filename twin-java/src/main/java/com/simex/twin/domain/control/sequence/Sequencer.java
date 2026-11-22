package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.control.ControlEvent;
import com.simex.twin.domain.control.alarm.AlarmRegistry;
import com.simex.twin.domain.shared.SimTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Máquina de estados de la secuencia. Los flancos de subida del inductivo y del fotoeléctrico se retienen mientras la
 * banda se mueve (también al frenar) y se atienden al volver a TRANSPORTE: así una detección simultánea o durante otro
 * ciclo no se pierde. Con la banda detenida no hay disparos nuevos.
 */
public final class Sequencer {

    // Cadenas de transiciones en un mismo scan (por ejemplo TRANSPORTE → ESTAMPADO ya frenado); la tabla no tiene
    // ciclos.
    private static final int MAX_TRANSITIONS_PER_SCAN = 4;

    private final Map<MachineState, StateHandler> handlers = new EnumMap<>(MachineState.class);
    private final SequenceContext ctx;
    private MachineState state = MachineState.OFF;
    private boolean previousInductive;
    private boolean previousPhotoelectric;
    private double previousSpeedMps;

    public Sequencer(SequenceParameters parameters, AlarmRegistry alarms, SequenceInput initial) {
        this.ctx = new SequenceContext(parameters, alarms, initial);
        StateHandler halted = new StateHandlers.Halted();
        handlers.put(MachineState.OFF, halted);
        handlers.put(MachineState.HEATING, new StateHandlers.Heating());
        handlers.put(MachineState.TRANSPORT, new StateHandlers.Transport());
        handlers.put(MachineState.DISCARD, new StateHandlers.Discard());
        handlers.put(MachineState.THERMAL_WAIT, new StateHandlers.ThermalWait());
        handlers.put(MachineState.STAMPING, new StateHandlers.Stamping());
        handlers.put(MachineState.EMERGENCY_STOP, halted);
        handlers.put(MachineState.FAULT, halted);
    }

    public SequenceOutput scan(SequenceInput in, List<ControlEvent> events) {
        ctx.in = in;
        ctx.now = in.now();
        ctx.events = events;
        latchRisingEdges(in);
        for (int i = 0; i < MAX_TRANSITIONS_PER_SCAN; i++) {
            Optional<MachineState> next = handlers.get(state).onScan(ctx);
            if (next.isEmpty()) {
                break;
            }
            transitionTo(next.get());
        }
        return ctx.output;
    }

    /** START: APAGADO → CALENTANDO. */
    public void start(SimTime now, List<ControlEvent> events) {
        if (state == MachineState.OFF) {
            transitionTo(MachineState.HEATING, now, events);
        }
    }

    /** STOP: inmediato en CALENTANDO, TRANSPORTE y ESPERA TÉRMICA; pendiente en DESCARTE y ESTAMPADO. */
    public void stop(SimTime now, List<ControlEvent> events) {
        switch (state) {
            case HEATING, TRANSPORT, THERMAL_WAIT -> transitionTo(MachineState.OFF, now, events);
            case DISCARD, STAMPING -> ctx.stopPending = true;
            default -> {}
        }
    }

    /** RESET: PARO o FALLA → APAGADO. */
    public void reset(SimTime now, List<ControlEvent> events) {
        if (state.isHalted()) {
            transitionTo(MachineState.OFF, now, events);
        }
    }

    /** Disparo de un interlock. */
    public void trip(MachineState target, SimTime now, List<ControlEvent> events) {
        if (state != target) {
            transitionTo(target, now, events);
        }
        ctx.output = SequenceOutput.ALL_OFF;
    }

    public MachineState state() {
        return state;
    }

    /** Duración medida de la última extensión del troquel. */
    public OptionalLong lastStampStrokeMs() {
        return ctx.lastStampStrokeMs;
    }

    private void latchRisingEdges(SequenceInput in) {
        double speedMps = in.inputs().beltSpeedMps();
        boolean moving = speedMps > 0 || previousSpeedMps > 0;
        if (moving && in.inputs().inductive() && !previousInductive) {
            ctx.inductivePending = true;
        }
        if (moving && in.inputs().photoelectric() && !previousPhotoelectric && ctx.stationDetectedAt.isEmpty()) {
            ctx.stationDetectedAt = Optional.of(in.now());
        }
        previousInductive = in.inputs().inductive();
        previousPhotoelectric = in.inputs().photoelectric();
        previousSpeedMps = speedMps;
    }

    private void transitionTo(MachineState next, SimTime now, List<ControlEvent> events) {
        ctx.now = now;
        ctx.events = events;
        transitionTo(next);
    }

    private void transitionTo(MachineState next) {
        handlers.get(state).onExit(ctx);
        ctx.events.add(new ControlEvent.StateChanged(state, next));
        state = next;
        ctx.stateEnteredAt = ctx.now;
        handlers.get(state).onEnter(ctx);
    }
}
