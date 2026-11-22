package com.simex.twin.domain.control.command;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.thermal.ControlMode;
import java.util.Optional;

/** Comandos de la máquina (patrón Command). Es sealed porque forma parte del contrato versionado. */
public sealed interface MachineCommand {

    CommandType type();

    record SetSetpoint(double setpointC) implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.SET_SETPOINT;
        }
    }

    record SetPid(double kp, double ki, double kd) implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.SET_PID;
        }
    }

    record SetBeltSpeed(double pwmDuty) implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.SET_BELT_SPEED;
        }
    }

    record SetMode(ControlMode mode) implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.SET_MODE;
        }
    }

    record Start() implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.START;
        }
    }

    record Stop() implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.STOP;
        }
    }

    record EmergencyStop() implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.EMERGENCY_STOP;
        }
    }

    record Reset() implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.RESET;
        }
    }

    /** {@code code} vacío reconoce todas (el {@code ALL} del contrato). */
    record AckAlarm(Optional<AlarmCode> code) implements MachineCommand {
        @Override
        public CommandType type() {
            return CommandType.ACK_ALARM;
        }
    }
}
