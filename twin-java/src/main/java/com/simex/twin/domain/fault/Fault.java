package com.simex.twin.domain.fault;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.SimDuration;
import java.util.Map;

/**
 * Una falla: función pura del tiempo desde su inicio, así que es determinista. Devuelve los modificadores con su aporte
 * (los factores multiplican y los desplazamientos suman); no toca el código de la física.
 */
public interface Fault {

    FaultType type();

    PlantModifiers contribute(PlantModifiers modifiers, SimDuration sinceStart);

    /** Parámetros efectivos, con los valores por defecto aplicados (para {@code FAULT_INJECTED}). */
    Map<String, Object> params();

    /** Avance lineal de una rampa de {@code rampS} segundos: 0 al empezar, 1 al terminar y después. */
    static double rampProgress(double rampS, SimDuration sinceStart) {
        return rampS <= 0 ? 1.0 : Math.min(1.0, sinceStart.seconds() / rampS);
    }

    /** Valor de una perilla que va linealmente de {@code neutral} a {@code target} durante la rampa. */
    static double ramp(double neutral, double target, double rampS, SimDuration sinceStart) {
        return neutral + (target - neutral) * rampProgress(rampS, sinceStart);
    }
}
