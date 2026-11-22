package com.simex.twin.domain.machine;

/** Contadores de piezas de la corrida. */
public record Counters(long metalDiscarded, long plasticStamped, long metalEscaped, long piecesArrived) {

    static final Counters ZERO = new Counters(0, 0, 0, 0);

    Counters arrived() {
        return new Counters(metalDiscarded, plasticStamped, metalEscaped, piecesArrived + 1);
    }

    Counters discarded() {
        return new Counters(metalDiscarded + 1, plasticStamped, metalEscaped, piecesArrived);
    }

    Counters stamped() {
        return new Counters(metalDiscarded, plasticStamped + 1, metalEscaped, piecesArrived);
    }

    Counters escaped() {
        return new Counters(metalDiscarded, plasticStamped, metalEscaped + 1, piecesArrived);
    }
}
