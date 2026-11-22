package com.simex.twin.domain.fault;

/** Tipos de falla inyectables (sección 4.10). */
public enum FaultType {
    HEATER_DEGRADATION,
    COLD_FRONT,
    SENSOR_NOISE,
    OFFSET_DRIFT,
    THERMOCOUPLE_OPEN,
    MOTOR_WEAR,
    PNEUMATIC_LEAK,
    METAL_BURST
}
