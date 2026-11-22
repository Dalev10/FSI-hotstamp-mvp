package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;

/**
 * Reloj de muestreo sobre tiempos ideales {@code n · período}: muestrea en el primer scan que alcanza el siguiente
 * instante ideal. Con un período que no es múltiplo del tick (LEGACY: 1010 ms), el período medio sigue exacto y la
 * variación es de un tick como máximo.
 */
final class SampleClock {

    private final long periodMs;
    private long nextSampleMs;

    SampleClock(SimDuration period) {
        this.periodMs = period.ms();
    }

    /** {@code true} si en este scan toca muestrear. */
    boolean isDue(SimTime now) {
        if (now.ms() < nextSampleMs) {
            return false;
        }
        nextSampleMs = (now.ms() / periodMs + 1) * periodMs;
        return true;
    }

    /** El próximo scan muestrea. */
    void sampleNow() {
        nextSampleMs = 0;
    }
}
