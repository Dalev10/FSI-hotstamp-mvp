package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;

/**
 * Amplificador LM358 de una sola fuente (mV → V): multiplica por la ganancia y satura entre 0 V y la referencia del
 * ADC. Con la termocupla abierta la entrada queda flotando y la salida satura arriba.
 */
public record AmplifierStage(double gain, double vrefV) implements SignalStage {

    private static final double MV_PER_V = 1000.0;

    @Override
    public double apply(double signalMv, PlantModifiers modifiers) {
        if (modifiers.thermocoupleOpen()) {
            return vrefV;
        }
        return Math.clamp(signalMv / MV_PER_V * gain, 0.0, vrefV);
    }
}
