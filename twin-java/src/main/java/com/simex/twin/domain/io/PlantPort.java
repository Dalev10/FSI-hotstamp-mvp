package com.simex.twin.domain.io;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Frontera entre el control y la planta. La implementa la planta simulada y podría implementarla una E/S real; el
 * control solo la ve a través de la imagen de proceso.
 */
public interface PlantPort {

    /** Muestrea los sensores (con su ruido) y los fines de carrera. */
    ProcessInputs readInputs();

    /** Aplica las salidas del scan: SSR, PWM del motor y válvulas. */
    void writeOutputs(ProcessOutputs outputs);

    /** Integra la física durante {@code dt}. */
    void advance(SimDuration dt);
}
