package com.simex.twin.domain.control;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Ventana de proceso y tiempos de la secuencia.
 *
 * @param stable tiempo seguido en ventana para considerarla estable
 */
public record ProcessParameters(
        double windowMinC,
        double windowMaxC,
        double bandHysteresisC,
        SimDuration stable,
        double defaultSetpointC,
        SimDuration heatupTimeout,
        SimDuration thermalWaitAlarm) {

    public boolean inWindow(double tempC) {
        return windowMinC <= tempC && tempC <= windowMaxC;
    }
}
