package com.simex.twin.application.engine;

import com.simex.twin.domain.shared.SimDuration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El hilo {@code sim-engine}: único que muta el dominio. Llama a {@link SimulationEngine#runCycle} a tasa fija de pared;
 * la tasa fija no acumula deriva, así que el avance simulado sigue al reloj en el largo plazo.
 */
public final class EngineScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EngineScheduler.class);

    private final SimulationEngine engine;
    private final long cycleMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sim-engine").daemon().factory());

    public EngineScheduler(SimulationEngine engine, SimDuration wallCycle) {
        this.engine = engine;
        this.cycleMs = wallCycle.ms();
    }

    public void start() {
        executor.scheduleAtFixedRate(this::cycle, 0, cycleMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(cycleMs * 10, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void cycle() {
        try {
            engine.runCycle();
        } catch (RuntimeException e) {
            // Una excepción sin capturar cancelaría la tarea periódica: el motor quedaría detenido en silencio.
            LOG.error("Fallo en el ciclo del motor", e);
        }
    }
}
