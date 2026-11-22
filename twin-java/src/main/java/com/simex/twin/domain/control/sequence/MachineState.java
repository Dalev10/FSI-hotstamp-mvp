package com.simex.twin.domain.control.sequence;

/** Estados de la secuencia (sección 4.6). */
public enum MachineState {
    OFF,
    HEATING,
    TRANSPORT,
    DISCARD,
    THERMAL_WAIT,
    STAMPING,
    EMERGENCY_STOP,
    FAULT;

    /** La S de LEGACY: la máquina está en marcha. */
    public boolean isOperative() {
        return this != OFF && !isHalted();
    }

    /** PARO o FALLA: salidas seguras hasta el RESET. */
    public boolean isHalted() {
        return this == EMERGENCY_STOP || this == FAULT;
    }

    /** Estados en los que se evalúan TEMP_LOW y TEMP_HIGH. */
    public boolean hasTemperatureAlarms() {
        return this == TRANSPORT || this == DISCARD || this == THERMAL_WAIT || this == STAMPING;
    }
}
