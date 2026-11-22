package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;

/** Una etapa física del acondicionamiento de la señal de la termocupla (patrón pipeline). */
public interface SignalStage {

    double apply(double signal, PlantModifiers modifiers);
}
