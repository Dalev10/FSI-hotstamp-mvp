package com.simex.twin.domain.machine;

import com.simex.twin.domain.control.ControlStatus;
import com.simex.twin.domain.shared.SimTime;

/**
 * Foto inmutable de la máquina tras un scan: lo que el adaptador convierte en telemetría.
 *
 * @param simTime tiempo simulado al final del scan
 */
public record MachineSnapshot(
        SimTime simTime,
        ControlStatus control,
        int queueLength,
        Counters counters,
        double heaterWh,
        double motorWh,
        GroundTruth groundTruth) {

    /**
     * Valores reales de la planta, solo para el dashboard y la evaluación.
     *
     * @param stampedOutOfWindow estampados con la temperatura real del troquel fuera de la ventana
     */
    public record GroundTruth(double platenTempC, double ambientC, double heaterEfficiency, long stampedOutOfWindow) {}
}
