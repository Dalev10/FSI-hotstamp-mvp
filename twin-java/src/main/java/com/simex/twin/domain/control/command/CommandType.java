package com.simex.twin.domain.control.command;

public enum CommandType {
    SET_SETPOINT,
    SET_PID,
    SET_BELT_SPEED,
    SET_MODE,
    START,
    STOP,
    EMERGENCY_STOP,
    RESET,
    ACK_ALARM;

    /** Ajustes de operación: los únicos que puede enviar el agente o los que no se aceptan en PARO/FALLA. */
    boolean isSetting() {
        return this == SET_SETPOINT || this == SET_PID || this == SET_BELT_SPEED || this == SET_MODE;
    }
}
