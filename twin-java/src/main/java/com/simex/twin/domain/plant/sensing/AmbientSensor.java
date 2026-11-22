package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.RandomStream;

/** Sensor de ambiente: temperatura ambiente real más N(0, σ). Su lectura compensa la junta fría en el firmware. */
public final class AmbientSensor {

    private final double sigmaC;
    private final RandomStream noise;

    public AmbientSensor(double ambientNoiseSigmaC, RandomStream noise) {
        this.sigmaC = ambientNoiseSigmaC;
        this.noise = noise;
    }

    public double read(double ambientC, PlantModifiers modifiers) {
        return modifiers.effectiveAmbientC(ambientC) + noise.nextGaussian() * sigmaC;
    }
}
