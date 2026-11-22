package com.simex.twin.domain.shared;

/** Instante simulado: milisegundos desde el inicio de la corrida. El dominio nunca consulta el reloj del sistema. */
public record SimTime(long ms) {

    public static final SimTime ZERO = new SimTime(0);

    public SimTime {
        if (ms < 0) {
            throw new DomainException("El tiempo simulado no puede ser negativo: " + ms + " ms");
        }
    }

    // Un desborde da un valor negativo, que el constructor rechaza.
    public SimTime plus(SimDuration duration) {
        return new SimTime(ms + duration.ms());
    }

    /** Duración desde {@code earlier}; falla si {@code earlier} es posterior a este instante. */
    public SimDuration minus(SimTime earlier) {
        return new SimDuration(ms - earlier.ms);
    }
}
