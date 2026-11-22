package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.RandomStream;

/**
 * Ruido gaussiano de medición: suma N(0, σ_T · S) con σ_T multiplicado por el factor de falla (mV → mV). Sortea en
 * cada lectura aunque σ sea 0, para que el flujo avance igual con cualquier configuración.
 */
public final class NoiseStage implements SignalStage {

    private final double sigmaMv;
    private final RandomStream noise;

    public NoiseStage(double noiseSigmaC, double seebeckMvPerC, RandomStream noise) {
        this.sigmaMv = noiseSigmaC * seebeckMvPerC;
        this.noise = noise;
    }

    @Override
    public double apply(double signalMv, PlantModifiers modifiers) {
        return signalMv + noise.nextGaussian() * sigmaMv * modifiers.noiseSigmaFactor();
    }
}
