package com.simex.twin.adapter.out.clock;

import com.simex.twin.application.port.out.WallClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Reloj del sistema, en milisegundos (la precisión de {@code ts}). */
public final class SystemWallClock implements WallClock {

    @Override
    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
