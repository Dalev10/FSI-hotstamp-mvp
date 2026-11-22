package com.simex.twin.application.port.out;

import java.time.Instant;

/** Reloj de pared. Solo lo usan el watchdog y el sello {@code ts}; el dominio nunca lo consulta. */
@FunctionalInterface
public interface WallClock {

    Instant now();
}
