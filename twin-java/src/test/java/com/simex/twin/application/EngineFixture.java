package com.simex.twin.application;

import static com.simex.twin.domain.DefaultParameters.TICK;

import com.simex.twin.adapter.out.scenario.ClasspathScenarioRepository;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.application.engine.SimulationEngine;
import com.simex.twin.application.port.out.TelemetryPublisher;
import com.simex.twin.application.port.out.WallClock;
import com.simex.twin.application.service.SimulationService;
import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.shared.SimDuration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Motor y servicio con los escenarios reales, un reloj falso y un publicador que registra todo. */
public final class EngineFixture {

    public static final int MAX_SPEED_FACTOR = 60;

    public final FakeWallClock clock;
    public final RecordingPublisher publisher = new RecordingPublisher();
    public final SimulationEngine engine;
    public final SimulationService service;
    private final AtomicInteger runs = new AtomicInteger();

    public EngineFixture() {
        this(new FakeWallClock());
    }

    public EngineFixture(FakeWallClock clock) {
        this(clock, clock);
    }

    /** @param wallClock el reloj del motor; {@code fake} queda accesible para avanzarlo a mano */
    public EngineFixture(WallClock wallClock, FakeWallClock fake) {
        this.clock = fake;
        this.engine = new SimulationEngine(
                DefaultParameters.plant(),
                DefaultParameters.control(),
                TICK,
                new SimDuration(1000),
                1000,
                publisher,
                wallClock,
                () -> new UUID(0, runs.incrementAndGet()).toString(), // con forma de UUID, como pide el contrato
                idleScenario());
        this.service = new SimulationService(
                engine,
                new ClasspathScenarioRepository(JsonMapper.builder().build()),
                DefaultParameters.control(),
                MAX_SPEED_FACTOR);
    }

    /** Ciclos de pared de 100 ms con el reloj falso. */
    public void cycles(int count) {
        for (int i = 0; i < count; i++) {
            engine.runCycle();
            clock.advanceMs(100);
        }
    }

    public static Scenario idleScenario() {
        return new Scenario(
                "reposo",
                "Máquina en reposo antes de la primera corrida",
                0,
                1,
                ControlMode.PID,
                new Scenario.InitialConditions(25, 25),
                new ArrivalProfile(10, 0.2),
                List.of(),
                List.of(),
                List.of());
    }

    public static final class FakeWallClock implements WallClock {
        private volatile Instant now = Instant.parse("2026-11-21T21:00:00Z");

        @Override
        public Instant now() {
            return now;
        }

        public void advanceMs(long ms) {
            now = now.plusMillis(ms);
        }
    }

    public static final class RecordingPublisher implements TelemetryPublisher {
        public final List<TelemetryFrame> telemetry = new CopyOnWriteArrayList<>();
        public final List<EventFrame> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishTelemetry(TelemetryFrame frame) {
            telemetry.add(frame);
        }

        @Override
        public void publishEvent(EventFrame frame) {
            events.add(frame);
        }

        public <T extends DomainEvent> List<T> eventsOf(Class<T> type) {
            return events.stream()
                    .map(EventFrame::event)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }
}
