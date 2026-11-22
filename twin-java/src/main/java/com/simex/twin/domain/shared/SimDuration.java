package com.simex.twin.domain.shared;

/** Duración en tiempo simulado, en milisegundos. */
public record SimDuration(long ms) {

    public static final SimDuration ZERO = new SimDuration(0);

    private static final double MS_PER_SECOND = 1000.0;

    public SimDuration {
        if (ms < 0) {
            throw new DomainException("Una duración no puede ser negativa: " + ms + " ms");
        }
    }

    /** Segundos, para integrar la física. */
    public double seconds() {
        return ms / MS_PER_SECOND;
    }
}
