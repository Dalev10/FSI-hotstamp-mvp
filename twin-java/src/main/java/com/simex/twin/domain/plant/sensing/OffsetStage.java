package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;

/** Offset de entrada del amplificador más la deriva de falla (mV → mV). */
public record OffsetStage(double inputOffsetMv) implements SignalStage {

    @Override
    public double apply(double signalMv, PlantModifiers modifiers) {
        return signalMv + inputOffsetMv + modifiers.offsetDriftMv();
    }
}
