package com.simex.twin.domain.plant.sensing;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.RandomStream;
import java.util.List;

/**
 * Cadena de medición de la planta, hasta el ADC: termopar tipo K lineal → offset → ruido → amplificador → ADC. Pasar
 * las cuentas a °C es trabajo del firmware (control).
 */
public final class ThermocoupleChain {

    private final double seebeckMvPerC;
    private final List<SignalStage> stages;
    private final Adc adc;

    public ThermocoupleChain(SensingParameters parameters, RandomStream noise) {
        this.seebeckMvPerC = parameters.seebeckMvPerC();
        this.stages = List.of(
                new OffsetStage(parameters.inputOffsetMv()),
                new NoiseStage(parameters.noiseSigmaC(), parameters.seebeckMvPerC(), noise),
                new AmplifierStage(parameters.amplifierGain(), parameters.adcVrefV()));
        this.adc = new Adc(parameters.adcBits(), parameters.adcVrefV());
    }

    /**
     * Cuentas del ADC.
     *
     * @param tipTempC temperatura de la punta de la termocupla
     * @param ambientC temperatura ambiente del escenario; la junta fría está a la temperatura ambiente real
     */
    public int measure(double tipTempC, double ambientC, PlantModifiers modifiers) {
        double signal = seebeckMvPerC * (tipTempC - modifiers.effectiveAmbientC(ambientC));
        for (SignalStage stage : stages) {
            signal = stage.apply(signal, modifiers);
        }
        return adc.convert(signal);
    }
}
