package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.control.ControlEvent;
import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimTime;
import java.util.Optional;
import java.util.OptionalLong;

/** Un handler por estado de la tabla 4.6. OFF, PARO y FALLA comparten "todo apagado". */
final class StateHandlers {

    private StateHandlers() {}

    /** APAGADO, PARO y FALLA: banda parada, térmico apagado y pistones retraídos. */
    static final class Halted implements StateHandler {
        @Override
        public void onEnter(SequenceContext ctx) {
            ctx.stopPending = false;
        }

        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            ctx.output = SequenceOutput.ALL_OFF;
            return Optional.empty();
        }
    }

    /** CALENTANDO: térmico activo hasta que la temperatura medida queda estable en la ventana. */
    static final class Heating implements StateHandler {
        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            ctx.output = SequenceOutput.heatingOnly();
            if (ctx.timeInState().ms() >= ctx.parameters.heatupTimeout().ms()) {
                ctx.alarms.raise(AlarmCode.HEATUP_TIMEOUT, ctx.now, Optional.empty(), ctx.events);
            }
            return ctx.in.inWindowStable() ? Optional.of(MachineState.TRANSPORT) : Optional.empty();
        }

        @Override
        public void onExit(SequenceContext ctx) {
            ctx.alarms.clear(AlarmCode.HEATUP_TIMEOUT, ctx.events);
        }
    }

    /**
     * TRANSPORTE: banda en marcha. Atiende los flancos retenidos: primero el inductivo (descarte) y luego la estación,
     * que estampa si la temperatura está en ventana o espera si no.
     */
    static final class Transport implements StateHandler {
        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            ctx.output =
                    new SequenceOutput(ctx.in.beltPwm(), ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED, true);
            if (ctx.inductivePending) {
                ctx.inductivePending = false;
                return Optional.of(MachineState.DISCARD);
            }
            if (ctx.stationDetectedAt.isPresent()) {
                return Optional.of(ctx.in.inWindow() ? MachineState.STAMPING : MachineState.THERMAL_WAIT);
            }
            return Optional.empty();
        }
    }

    /** DESCARTE: frena hasta 0, extiende el pistón al detenerse y lo retrae al llegar. */
    static final class Discard implements StateHandler {

        private enum Phase {
            BRAKE,
            EXTEND,
            RETRACT
        }

        private Phase phase = Phase.BRAKE;

        @Override
        public void onEnter(SequenceContext ctx) {
            phase = Phase.BRAKE;
        }

        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            CylinderPosition position = ctx.in.inputs().discardPosition();
            if (phase == Phase.BRAKE && ctx.beltStopped()) {
                phase = Phase.EXTEND;
            }
            if (phase == Phase.EXTEND && position == CylinderPosition.EXTENDED) {
                phase = Phase.RETRACT;
            }
            if (phase == Phase.RETRACT && position == CylinderPosition.RETRACTED) {
                ctx.output = SequenceOutput.heatingOnly();
                return Optional.of(ctx.stopPending ? MachineState.OFF : MachineState.TRANSPORT);
            }
            ValveCommand valve = phase == Phase.EXTEND ? ValveCommand.ENERGIZED : ValveCommand.DE_ENERGIZED;
            ctx.output = new SequenceOutput(DutyCycle.ZERO, valve, ValveCommand.DE_ENERGIZED, true);
            return Optional.empty();
        }
    }

    /** ESPERA TÉRMICA: pieza en la estación y temperatura fuera de ventana; espera a que se estabilice. */
    static final class ThermalWait implements StateHandler {
        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            ctx.output = SequenceOutput.heatingOnly();
            if (ctx.timeInState().ms() >= ctx.parameters.thermalWaitAlarm().ms()) {
                ctx.alarms.raise(AlarmCode.THERMAL_WAIT_LONG, ctx.now, Optional.empty(), ctx.events);
            }
            return ctx.in.inWindowStable() ? Optional.of(MachineState.STAMPING) : Optional.empty();
        }

        @Override
        public void onExit(SequenceContext ctx) {
            ctx.alarms.clear(AlarmCode.THERMAL_WAIT_LONG, ctx.events);
        }
    }

    /** ESTAMPADO: frena hasta 0, extiende, mantiene la permanencia y retrae; al terminar informa el ciclo. */
    static final class Stamping implements StateHandler {

        private enum Phase {
            BRAKE,
            EXTEND,
            DWELL,
            RETRACT
        }

        private Phase phase = Phase.BRAKE;
        private SimTime extendStartedAt = SimTime.ZERO;
        private SimTime dwellStartedAt = SimTime.ZERO;
        private double tempAtStartC;
        private long waitMs;
        private long strokeMs;

        @Override
        public void onEnter(SequenceContext ctx) {
            phase = Phase.BRAKE;
        }

        @Override
        public Optional<MachineState> onScan(SequenceContext ctx) {
            CylinderPosition position = ctx.in.inputs().stampPosition();
            if (phase == Phase.BRAKE && ctx.beltStopped()) {
                phase = Phase.EXTEND;
                extendStartedAt = ctx.now;
                tempAtStartC = ctx.in.measuredTempC();
                waitMs = ctx.stationDetectedAt.map(at -> ctx.now.minus(at).ms()).orElse(0L);
            }
            if (phase == Phase.EXTEND && position == CylinderPosition.EXTENDED) {
                phase = Phase.DWELL;
                dwellStartedAt = ctx.now;
                strokeMs = ctx.now.minus(extendStartedAt).ms();
                ctx.lastStampStrokeMs = OptionalLong.of(strokeMs);
            }
            if (phase == Phase.DWELL
                    && ctx.now.minus(dwellStartedAt).ms()
                            >= ctx.parameters.stampDwell().ms()) {
                phase = Phase.RETRACT;
            }
            if (phase == Phase.RETRACT && position == CylinderPosition.RETRACTED) {
                ctx.events.add(new ControlEvent.StampCompleted(tempAtStartC, waitMs, strokeMs));
                ctx.stationDetectedAt = Optional.empty();
                ctx.output = SequenceOutput.heatingOnly();
                return Optional.of(ctx.stopPending ? MachineState.OFF : MachineState.TRANSPORT);
            }
            boolean extended = phase == Phase.EXTEND || phase == Phase.DWELL;
            ctx.output = new SequenceOutput(
                    DutyCycle.ZERO,
                    ValveCommand.DE_ENERGIZED,
                    extended ? ValveCommand.ENERGIZED : ValveCommand.DE_ENERGIZED,
                    true);
            return Optional.empty();
        }
    }
}
