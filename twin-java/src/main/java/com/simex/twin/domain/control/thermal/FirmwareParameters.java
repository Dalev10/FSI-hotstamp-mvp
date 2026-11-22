package com.simex.twin.domain.control.thermal;

/**
 * Constantes de la cadena de medición tal como las conoce el firmware, más su compensación de junta fría.
 *
 * @param seebeckMvPerC sensibilidad del termopar tipo K
 * @param amplifierGain ganancia del amplificador
 * @param adcBits resolución del ADC
 * @param adcVrefV tensión de referencia del ADC
 * @param coldJunction origen de la compensación de junta fría
 * @param fixedColdJunctionC junta fría supuesta en modo {@link ColdJunctionMode#FIXED}
 */
public record FirmwareParameters(
        double seebeckMvPerC,
        double amplifierGain,
        int adcBits,
        double adcVrefV,
        ColdJunctionMode coldJunction,
        double fixedColdJunctionC) {}
