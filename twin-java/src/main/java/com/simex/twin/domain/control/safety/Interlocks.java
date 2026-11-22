package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.io.CylinderPosition;
import java.util.Optional;

/** Los cuatro interlocks de la sección 4.7. */
public final class Interlocks {

    private Interlocks() {}

    /** Prioridad 1: el paro de emergencia enclavado. Lo libera el {@code RESET}, así que nunca queda como causa. */
    public static final class EmergencyStopInterlock implements Interlock {
        @Override
        public AlarmCode alarm() {
            return AlarmCode.EMERGENCY_STOP;
        }

        @Override
        public Optional<Trip> evaluate(SafetyInput in) {
            return in.estopLatched()
                    ? Optional.of(new Trip(MachineState.EMERGENCY_STOP, alarm(), Optional.empty()))
                    : Optional.empty();
        }

        @Override
        public boolean causeActive(SafetyInput in) {
            return false;
        }
    }

    /** Prioridad 2: con la termocupla abierta el ADC satura. */
    public record ThermocoupleOpenInterlock(int countsThreshold) implements Interlock {
        @Override
        public AlarmCode alarm() {
            return AlarmCode.THERMOCOUPLE_OPEN;
        }

        @Override
        public Optional<Trip> evaluate(SafetyInput in) {
            return in.tcCounts() >= countsThreshold ? fault(alarm()) : Optional.empty();
        }
    }

    /** Prioridad 3. */
    public record OverTemperatureInterlock(double tripC) implements Interlock {
        @Override
        public AlarmCode alarm() {
            return AlarmCode.OVER_TEMPERATURE;
        }

        @Override
        public Optional<Trip> evaluate(SafetyInput in) {
            return in.measuredTempC() >= tripC ? fault(alarm()) : Optional.empty();
        }
    }

    /**
     * Prioridad 4: un cilindro que no llega a su fin de carrera, al extender o al retraer, dentro del tiempo límite
     * desde el último mando de su válvula. La causa persiste mientras algún cilindro no esté retraído.
     */
    public record PneumaticTimeoutInterlock(long timeoutMs) implements Interlock {
        @Override
        public AlarmCode alarm() {
            return AlarmCode.PNEUMATIC_TIMEOUT;
        }

        @Override
        public Optional<Trip> evaluate(SafetyInput in) {
            if (stuck(in.stamp(), in)) {
                return Optional.of(new Trip(MachineState.FAULT, alarm(), Optional.of("STAMP")));
            }
            if (stuck(in.discard(), in)) {
                return Optional.of(new Trip(MachineState.FAULT, alarm(), Optional.of("DISCARD")));
            }
            return Optional.empty();
        }

        @Override
        public boolean causeActive(SafetyInput in) {
            return in.stamp().position() != CylinderPosition.RETRACTED
                    || in.discard().position() != CylinderPosition.RETRACTED;
        }

        private boolean stuck(SafetyInput.Cylinder cylinder, SafetyInput in) {
            return cylinder.position() != cylinder.target()
                    && in.now().ms() - cylinder.commandedAt().ms() >= timeoutMs;
        }
    }

    private static Optional<Interlock.Trip> fault(AlarmCode alarm) {
        return Optional.of(new Interlock.Trip(MachineState.FAULT, alarm, Optional.empty()));
    }
}
