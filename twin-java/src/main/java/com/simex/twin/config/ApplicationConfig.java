package com.simex.twin.config;

import com.simex.twin.adapter.out.clock.SystemWallClock;
import com.simex.twin.adapter.out.metrics.MicrometerEngineMetrics;
import com.simex.twin.adapter.out.scenario.ClasspathScenarioRepository;
import com.simex.twin.adapter.out.websocket.WebSocketTelemetryPublisher;
import com.simex.twin.application.engine.EngineScheduler;
import com.simex.twin.application.engine.SimulationEngine;
import com.simex.twin.application.port.in.QueryStateUseCase;
import com.simex.twin.application.port.out.EngineMetrics;
import com.simex.twin.application.port.out.ScenarioRepository;
import com.simex.twin.application.port.out.WallClock;
import com.simex.twin.application.service.SimulationService;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.shared.SimDuration;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Cableado del hexágono: motor, scheduler, servicio, adaptadores de salida y la corrida automática. */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

    @Bean
    ScenarioRepository scenarioRepository(ObjectMapper mapper) {
        return new ClasspathScenarioRepository(mapper);
    }

    @Bean
    WallClock wallClock() {
        return new SystemWallClock();
    }

    /** La vista se pide perezosamente: el motor necesita al publicador para construirse. */
    @Bean(initMethod = "start", destroyMethod = "close")
    WebSocketTelemetryPublisher telemetryPublisher(
            TwinProperties properties, ObjectMapper mapper, WallClock clock, ObjectProvider<QueryStateUseCase> query) {
        return new WebSocketTelemetryPublisher(
                properties.telemetry().outboundQueueCapacity(),
                mapper,
                () -> query.getObject().view(),
                clock);
    }

    @Bean
    EngineMetrics engineMetrics(MeterRegistry registry) {
        return new MicrometerEngineMetrics(registry);
    }

    /** {@code twin.ws.sessions} y {@code twin.telemetry.dropped}, leídas del publicador. */
    @Bean
    MeterBinder telemetryMeters(WebSocketTelemetryPublisher publisher) {
        return registry -> {
            Gauge.builder("twin.ws.sessions", publisher, WebSocketTelemetryPublisher::sessions)
                    .description("Sesiones WebSocket suscritas a /ws/telemetry")
                    .register(registry);
            FunctionCounter.builder("twin.telemetry.dropped", publisher, WebSocketTelemetryPublisher::dropped)
                    .description("Telemetrías descartadas por cola de salida llena")
                    .register(registry);
        };
    }

    @Bean
    SimulationEngine simulationEngine(
            TwinProperties properties, WebSocketTelemetryPublisher publisher, EngineMetrics metrics, WallClock clock) {
        return new SimulationEngine(
                properties.toPlantParameters(),
                properties.toControlParameters(),
                new SimDuration(properties.engine().tickMs()),
                new SimDuration(properties.telemetry().periodSimMs()),
                properties.commands().idempotencyCapacity(),
                publisher,
                metrics,
                clock,
                () -> UUID.randomUUID().toString(),
                idle(properties));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    EngineScheduler engineScheduler(SimulationEngine engine, TwinProperties properties) {
        return new EngineScheduler(engine, new SimDuration(properties.engine().wallCycleMs()));
    }

    @Bean
    SimulationService simulationService(
            SimulationEngine engine, ScenarioRepository scenarios, TwinProperties properties) {
        return new SimulationService(
                engine,
                scenarios,
                properties.toControlParameters(),
                properties.engine().maxSpeedFactor());
    }

    @Bean
    GracefulShutdown gracefulShutdown(
            SimulationService service, WebSocketTelemetryPublisher publisher, TwinProperties properties) {
        return new GracefulShutdown(
                service, publisher, Duration.ofMillis(properties.engine().shutdownTimeoutMs()));
    }

    /** {@code twin.autostart.scenario} vacío: el gemelo queda en reposo hasta {@code POST /sim/scenario}. */
    @Bean
    ApplicationRunner autostart(SimulationService service, TwinProperties properties) {
        return args -> {
            TwinProperties.Autostart autostart = properties.autostart();
            if (!autostart.scenario().isBlank()) {
                service.startRun(
                                autostart.scenario(),
                                Optional.of(autostart.seed()),
                                autostart.speedFactor(),
                                Optional.empty())
                        .get(properties.commands().applyTimeoutMs(), TimeUnit.MILLISECONDS);
            }
        };
    }

    /** La máquina apagada a temperatura inicial que se muestra antes de la primera corrida. */
    private static Scenario idle(TwinProperties properties) {
        double initialC = properties.thermal().initialTempC();
        return new Scenario(
                "reposo",
                "Máquina en reposo antes de la primera corrida",
                0,
                1,
                properties.control().defaultMode(),
                new Scenario.InitialConditions(initialC, initialC),
                new ArrivalProfile(10, 0.2),
                List.of(),
                List.of(),
                List.of());
    }
}
