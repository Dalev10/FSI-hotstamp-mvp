package com.simex.twin.domain.shared;

/** Porcentaje en [0, 100], por ejemplo la salida de un controlador térmico. */
public record Percent(double value) {

    public static final double MAX = 100.0;

    public Percent {
        if (!(value >= 0 && value <= MAX)) {
            throw new DomainException("Porcentaje fuera de [0, 100]: " + value);
        }
        value += 0.0; // -0.0 → 0.0: los records comparan los double bit a bit
    }

    public DutyCycle toDutyCycle() {
        return new DutyCycle(value / MAX);
    }
}
