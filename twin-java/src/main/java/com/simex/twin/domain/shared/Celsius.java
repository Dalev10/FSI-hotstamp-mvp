package com.simex.twin.domain.shared;

/** Temperatura absoluta en °C. Las diferencias de temperatura, que pueden ser negativas, se modelan como double. */
public record Celsius(double value) {

    public static final double ABSOLUTE_ZERO_C = -273.15;

    public Celsius {
        if (!Double.isFinite(value) || value < ABSOLUTE_ZERO_C) {
            throw new DomainException("Temperatura inválida: " + value + " °C");
        }
        value += 0.0; // -0.0 → 0.0: los records comparan los double bit a bit
    }
}
