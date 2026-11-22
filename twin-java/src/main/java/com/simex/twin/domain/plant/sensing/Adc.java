package com.simex.twin.domain.plant.sensing;

/** Conversor analógico-digital: {@code counts = round(V / vref · (2^bits − 1))}, limitado a [0, 2^bits − 1]. */
public final class Adc {

    private final double vrefV;
    private final int maxCounts;

    public Adc(int bits, double vrefV) {
        this.vrefV = vrefV;
        this.maxCounts = (1 << bits) - 1;
    }

    public int convert(double volts) {
        return Math.clamp(Math.round(volts / vrefV * maxCounts), 0, maxCounts);
    }
}
