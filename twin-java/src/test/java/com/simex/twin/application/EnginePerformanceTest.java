package com.simex.twin.application;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.adapter.out.scenario.ClasspathScenarioRepository;
import com.simex.twin.application.engine.EngineScheduler;
import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.scenario.ScenarioRunner;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Sección 9.1 y criterio de la T8. Corre solo con {@code ./mvnw verify -Pperf}: mide tiempo real. */
@Tag("perf")
class EnginePerformanceTest {

    @Test
    void oneSimulatedHourInMaxModeTakesLessThanTwoSeconds() {
        EngineFixture f = new EngineFixture();
        f.service.startRun("nominal", Optional.empty(), 1, Optional.empty());
        f.engine.runMax(1); // calentamiento del JIT fuera de la medida
        long start = System.nanoTime();
        f.engine.runMax(36_000);
        double seconds = (System.nanoTime() - start) / 1e9;

        // nominal dura 3600 s: la corrida termina ahí aunque se pidan más ticks.
        assertThat(f.engine.view().status().simTimeMs()).isEqualTo(3_600_000);
        System.out.printf("perf: 1 h simulada en MAX en %.3f s%n", seconds);
        assertThat(seconds).isLessThan(2.0);
    }

    @Test
    void tickP99IsBelowPoint2Milliseconds() {
        Scenario nominal = new ClasspathScenarioRepository(JsonMapper.builder().build())
                .find("nominal")
                .orElseThrow();
        ScenarioRunner runner =
                new ScenarioRunner(nominal, 42, DefaultParameters.plant(), DefaultParameters.control(), TICK);
        for (int i = 0; i < 6_000; i++) { // calentamiento
            runner.step(i * 100L);
        }
        long[] nanos = new long[30_000];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            runner.step(i * 100L);
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);

        double p99Ms = nanos[(int) (nanos.length * 0.99)] / 1e6;
        System.out.printf("perf: tick p50 %.4f ms, p99 %.4f ms%n", nanos[nanos.length / 2] / 1e6, p99Ms);
        assertThat(p99Ms).isLessThan(0.2);
    }

    /** Criterio de la T8: con ×10, en 1 s de pared avanzan 10 s simulados (±2 %), medido sobre 10 s reales. */
    @Test
    void atTimesTenTheSchedulerFollowsTheWallClock() throws InterruptedException {
        EngineFixture f = new EngineFixture(Instant::now, new EngineFixture.FakeWallClock());
        try (EngineScheduler scheduler = new EngineScheduler(f.engine, TICK)) {
            f.service.startRun("nominal", Optional.empty(), 10, Optional.empty());
            scheduler.start();
            Thread.sleep(1_000);
            long wall0 = System.nanoTime();
            long sim0 = f.engine.view().status().simTimeMs();
            Thread.sleep(10_000);
            long sim1 = f.engine.view().status().simTimeMs();
            double wallS = (System.nanoTime() - wall0) / 1e9;

            double rate = (sim1 - sim0) / 1000.0 / wallS;
            System.out.printf("perf: a ×10, %.3f s simulados por segundo de pared%n", rate);
            assertThat(rate).isCloseTo(10.0, within(0.2));
        }
    }
}
