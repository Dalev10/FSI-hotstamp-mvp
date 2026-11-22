package com.simex.twin.application.port.out;

/** Métricas del motor. Se llama desde el hilo del motor en cada tick: la implementación debe ser barata. */
@FunctionalInterface
public interface EngineMetrics {

    /** Sin métricas (pruebas y uso sin Spring). */
    EngineMetrics NONE = nanos -> {};

    /** Duración de pared de un tick de simulación. */
    void tick(long nanos);
}
