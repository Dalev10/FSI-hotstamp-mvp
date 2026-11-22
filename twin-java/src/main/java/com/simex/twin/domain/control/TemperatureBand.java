package com.simex.twin.domain.control;

/** Semáforo de temperatura: LOW < mín ≤ OK ≤ máx < HIGH, con histéresis para volver a OK. */
public enum TemperatureBand {
    LOW,
    OK,
    HIGH;

    TemperatureBand next(double tempC, ProcessParameters p) {
        return switch (this) {
            case OK -> tempC < p.windowMinC() ? LOW : tempC > p.windowMaxC() ? HIGH : OK;
            case LOW -> tempC >= p.windowMinC() + p.bandHysteresisC() ? OK.next(tempC, p) : LOW;
            case HIGH -> tempC <= p.windowMaxC() - p.bandHysteresisC() ? OK.next(tempC, p) : HIGH;
        };
    }
}
