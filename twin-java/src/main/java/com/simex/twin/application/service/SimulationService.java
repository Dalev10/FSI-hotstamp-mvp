package com.simex.twin.application.service;

import com.simex.twin.application.engine.EngineRequest;
import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.engine.EngineRequest.CommandOutcome;
import com.simex.twin.application.engine.SimulationEngine;
import com.simex.twin.application.engine.SimulationEngine.EngineView;
import com.simex.twin.application.engine.SimulationStatus;
import com.simex.twin.application.port.in.ApplyCommandUseCase;
import com.simex.twin.application.port.in.FaultInjectionUseCase;
import com.simex.twin.application.port.in.HeartbeatUseCase;
import com.simex.twin.application.port.in.QueryStateUseCase;
import com.simex.twin.application.port.in.SimulationControlUseCase;
import com.simex.twin.application.port.out.ScenarioRepository;
import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.Fault;
import com.simex.twin.domain.fault.FaultFactory;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.shared.SimDuration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Implementación delgada de los cinco casos de uso: valida lo que puede validarse fuera del motor y encola el resto
 * para el hilo del motor.
 */
public final class SimulationService
        implements ApplyCommandUseCase,
                HeartbeatUseCase,
                SimulationControlUseCase,
                FaultInjectionUseCase,
                QueryStateUseCase {

    private static final double MS_PER_SECOND = 1000.0;

    private final SimulationEngine engine;
    private final ScenarioRepository scenarios;
    private final ControlParameters controlParameters;
    private final int maxSpeedFactor;

    public SimulationService(
            SimulationEngine engine,
            ScenarioRepository scenarios,
            ControlParameters controlParameters,
            int maxSpeedFactor) {
        this.engine = engine;
        this.scenarios = scenarios;
        this.controlParameters = controlParameters;
        this.maxSpeedFactor = maxSpeedFactor;
    }

    @Override
    public CompletableFuture<CommandOutcome> apply(IssuedCommand command) {
        CompletableFuture<CommandOutcome> result = new CompletableFuture<>();
        engine.submit(new EngineRequest.ApplyCommand(command, result));
        return result;
    }

    @Override
    public void heartbeat() {
        engine.submit(new EngineRequest.Heartbeat());
    }

    @Override
    public List<Scenario> scenarios() {
        return scenarios.all();
    }

    @Override
    public CompletableFuture<String> startRun(
            String scenarioId, Optional<Long> seed, int speedFactor, Optional<ControlMode> controlMode) {
        Scenario scenario = scenarios.find(scenarioId).orElseThrow(() -> new UnknownScenarioException(scenarioId));
        requireSpeed(speedFactor);
        CompletableFuture<String> runId = new CompletableFuture<>();
        engine.submit(new EngineRequest.StartRun(scenario, seed, speedFactor, controlMode, runId));
        return runId;
    }

    @Override
    public CompletableFuture<SimulationStatus> control(Action action, OptionalInt speedFactor) {
        if (action == Action.SET_SPEED) {
            requireSpeed(speedFactor.orElseThrow(() -> new IllegalArgumentException("SET_SPEED requiere speedFactor")));
        }
        CompletableFuture<SimulationStatus> status = new CompletableFuture<>();
        engine.submit(new EngineRequest.Control(action, speedFactor, status));
        return status;
    }

    @Override
    public CompletableFuture<Optional<String>> inject(
            FaultType type, Map<String, Object> params, Optional<Double> durationS) {
        Fault fault = FaultFactory.create(type, params); // parámetros inválidos: falla aquí, antes de encolar
        CompletableFuture<Optional<String>> faultId = new CompletableFuture<>();
        engine.submit(new EngineRequest.InjectFault(
                fault, durationS.map(s -> new SimDuration(Math.round(s * MS_PER_SECOND))), faultId));
        return faultId;
    }

    @Override
    public CompletableFuture<Boolean> remove(String faultId) {
        CompletableFuture<Boolean> removed = new CompletableFuture<>();
        engine.submit(new EngineRequest.RemoveFault(faultId, removed));
        return removed;
    }

    @Override
    public List<CatalogEntry> catalog() {
        return Arrays.stream(FaultType.values())
                .map(type -> new CatalogEntry(type, FaultFactory.paramNames(type), defaults(type)))
                .toList();
    }

    @Override
    public EngineView view() {
        return engine.view();
    }

    @Override
    public ControlParameters controlParameters() {
        return controlParameters;
    }

    private void requireSpeed(int speedFactor) {
        if (speedFactor < 1 || speedFactor > maxSpeedFactor) {
            throw new IllegalArgumentException("speedFactor fuera de [1, " + maxSpeedFactor + "]: " + speedFactor);
        }
    }

    /** Los parámetros efectivos de una falla creada sin parámetros; {@code cylinder} no tiene valor por defecto. */
    private static Map<String, Object> defaults(FaultType type) {
        Map<String, Object> required = type == FaultType.PNEUMATIC_LEAK ? Map.of("cylinder", "BOTH") : Map.of();
        Map<String, Object> defaults =
                new LinkedHashMap<>(FaultFactory.create(type, required).params());
        defaults.remove("cylinder");
        return defaults;
    }
}
