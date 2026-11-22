package com.simex.twin.adapter.out.metrics;

import com.simex.twin.application.port.out.EngineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/** {@code twin.tick.duration}: duración de pared de cada tick, con p50 y p99. */
public final class MicrometerEngineMetrics implements EngineMetrics {

    private final Timer tick;

    public MicrometerEngineMetrics(MeterRegistry registry) {
        this.tick = Timer.builder("twin.tick.duration")
                .description("Duración de pared de un tick de simulación")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
    }

    @Override
    public void tick(long nanos) {
        tick.record(nanos, TimeUnit.NANOSECONDS);
    }
}
