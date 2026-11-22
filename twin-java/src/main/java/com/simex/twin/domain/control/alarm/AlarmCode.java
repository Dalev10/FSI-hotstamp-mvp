package com.simex.twin.domain.control.alarm;

/**
 * Catálogo de alarmas (sección 4.8). Las críticas quedan enclavadas: siguen activas hasta el {@code RESET} y este exige
 * reconocerlas antes. Las advertencias se despejan solas.
 */
public enum AlarmCode {
    TEMP_LOW(Severity.WARNING),
    TEMP_HIGH(Severity.WARNING),
    HEATUP_TIMEOUT(Severity.WARNING),
    THERMAL_WAIT_LONG(Severity.WARNING),
    WATCHDOG_TIMEOUT(Severity.WARNING),
    EMERGENCY_STOP(Severity.CRITICAL),
    THERMOCOUPLE_OPEN(Severity.CRITICAL),
    OVER_TEMPERATURE(Severity.CRITICAL),
    PNEUMATIC_TIMEOUT(Severity.CRITICAL);

    private final Severity severity;

    AlarmCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    /** Enclavada y con reconocimiento obligatorio. */
    public boolean latched() {
        return severity == Severity.CRITICAL;
    }
}
