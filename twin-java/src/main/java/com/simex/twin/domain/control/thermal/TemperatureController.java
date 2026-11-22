package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.Percent;

/**
 * Estrategia de control térmico (patrón Strategy). Contrato común: salida siempre en [0, 100] %, determinista y sin
 * efectos fuera de su estado.
 */
public interface TemperatureController {

    /** Salida para este scan; entre muestreos se mantiene la anterior. */
    Percent compute(ControllerInput input);

    /** Reinicia el estado interno para continuar desde {@code state} sin salto; muestrea en el próximo scan. */
    void reset(ControllerState state);

    ControllerState snapshot();
}
