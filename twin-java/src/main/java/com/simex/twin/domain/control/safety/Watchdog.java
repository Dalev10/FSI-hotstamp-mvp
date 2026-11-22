package com.simex.twin.domain.control.safety;

import java.util.OptionalLong;

/**
 * Watchdog del agente sobre tiempo de pared (el motor se lo pasa; el dominio no consulta el reloj). Se arma con el
 * primer latido; si pasan más de {@code timeoutMs} sin latidos, vence una sola vez hasta que el agente vuelve.
 */
public final class Watchdog {

    public enum Change {
        NONE,
        ARMED,
        TIMED_OUT,
        RECOVERED
    }

    private final long timeoutMs;
    private boolean armed;
    private boolean timedOut;
    private long lastHeartbeatWallMs;

    public Watchdog(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Change heartbeat(long wallMs) {
        lastHeartbeatWallMs = wallMs;
        if (!armed) {
            armed = true;
            return Change.ARMED;
        }
        if (timedOut) {
            timedOut = false;
            return Change.RECOVERED;
        }
        return Change.NONE;
    }

    public Change check(long wallMs) {
        if (armed && !timedOut && wallMs - lastHeartbeatWallMs > timeoutMs) {
            timedOut = true;
            return Change.TIMED_OUT;
        }
        return Change.NONE;
    }

    public Supervisor supervisor() {
        return armed && !timedOut ? Supervisor.AGENT : Supervisor.NONE;
    }

    /** Tiempo desde el último latido; vacío antes del primero. */
    public OptionalLong heartbeatAgeMs(long wallMs) {
        return armed ? OptionalLong.of(wallMs - lastHeartbeatWallMs) : OptionalLong.empty();
    }
}
