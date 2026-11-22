package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.Percent;

/**
 * Réplica del control térmico del ITO 2: usa solo la salida Resistencia de {@link LegacyLogic}, con
 * {@code T = T_meas ≥ umbral}, evaluada cada {@code sample} y mantenida entre muestreos. Es todo o nada (0 o 100 %) y
 * maneja el SSR directo, sin ventana proporcional, como el sketch.
 */
public final class LegacyOnOffController implements TemperatureController {

    private static final Percent ON = new Percent(Percent.MAX);
    private static final Percent OFF = new Percent(0);

    private final double thresholdC;
    private final SampleClock clock;
    private Percent output = OFF;

    public LegacyOnOffController(LegacyParameters parameters) {
        this.thresholdC = parameters.thresholdC();
        this.clock = new SampleClock(parameters.sample());
    }

    @Override
    public Percent compute(ControllerInput in) {
        if (clock.isDue(in.now())) {
            boolean heater = LegacyLogic.evaluate(
                            in.operative(), in.presence(), in.inductive(), in.measuredTempC() >= thresholdC)
                    .heater();
            output = heater ? ON : OFF;
        }
        return output;
    }

    @Override
    public void reset(ControllerState state) {
        output = state.output();
        clock.sampleNow();
    }

    @Override
    public ControllerState snapshot() {
        return new ControllerState(output);
    }
}
