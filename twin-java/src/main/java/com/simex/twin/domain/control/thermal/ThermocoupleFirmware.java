package com.simex.twin.domain.control.thermal;

/**
 * Conversión del firmware de cuentas del ADC a temperatura:
 * {@code T_meas = counts · vref / (2^bits − 1) / gain / S + T_cj}, con {@code T_cj} leído del sensor de ambiente o
 * fijo según la configuración.
 */
public final class ThermocoupleFirmware {

    private static final double MV_PER_V = 1000.0;

    private final FirmwareParameters parameters;
    private final double inputMvPerCount;

    public ThermocoupleFirmware(FirmwareParameters parameters) {
        this.parameters = parameters;
        int maxCounts = (1 << parameters.adcBits()) - 1;
        this.inputMvPerCount = parameters.adcVrefV() * MV_PER_V / maxCounts / parameters.amplifierGain();
    }

    /** Tensión de entrada de la termocupla reconstruida desde las cuentas (la {@code tcMillivolts} de la telemetría). */
    public double inputMillivolts(int counts) {
        return counts * inputMvPerCount;
    }

    /** Temperatura medida. */
    public double temperatureC(int counts, double ambientReadingC) {
        return inputMillivolts(counts) / parameters.seebeckMvPerC() + coldJunctionC(ambientReadingC);
    }

    private double coldJunctionC(double ambientReadingC) {
        return switch (parameters.coldJunction()) {
            case AMBIENT_SENSOR -> ambientReadingC;
            case FIXED -> parameters.fixedColdJunctionC();
        };
    }
}
