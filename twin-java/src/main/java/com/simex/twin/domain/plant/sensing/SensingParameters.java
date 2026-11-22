package com.simex.twin.domain.plant.sensing;

/**
 * Hardware real de la cadena de medición (sección 4.2 del plan). El firmware tiene su propia copia de las constantes.
 *
 * @param seebeckMvPerC sensibilidad del termopar tipo K
 * @param amplifierGain ganancia del amplificador LM358
 * @param adcBits resolución del ADC
 * @param adcVrefV tensión de referencia del ADC; el amplificador satura en ella
 * @param inputOffsetMv offset de entrada del amplificador
 * @param noiseSigmaC desviación del ruido de medición, expresada en °C
 * @param ambientNoiseSigmaC desviación del ruido del sensor de ambiente
 */
public record SensingParameters(
        double seebeckMvPerC,
        double amplifierGain,
        int adcBits,
        double adcVrefV,
        double inputOffsetMv,
        double noiseSigmaC,
        double ambientNoiseSigmaC) {}
