package com.simex.twin.domain.shared;

/** Ciclo útil en [0, 1], por ejemplo el PWM del motor o la fracción de una ventana del SSR. */
public record DutyCycle(double value) {

    public static final DutyCycle ZERO = new DutyCycle(0);

    public DutyCycle {
        if (!(value >= 0 && value <= 1)) {
            throw new DomainException("Ciclo útil fuera de [0, 1]: " + value);
        }
        value += 0.0; // -0.0 → 0.0: los records comparan los double bit a bit
    }

    public Percent toPercent() {
        return new Percent(value * Percent.MAX);
    }
}
