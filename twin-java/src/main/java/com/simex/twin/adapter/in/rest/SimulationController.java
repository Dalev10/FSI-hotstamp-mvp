package com.simex.twin.adapter.in.rest;

import com.simex.twin.adapter.contract.ContractJson;
import com.simex.twin.adapter.in.rest.EngineCalls.NoActiveRunException;
import com.simex.twin.adapter.in.rest.EngineCalls.NotFoundException;
import com.simex.twin.adapter.in.rest.dto.RestDtos;
import com.simex.twin.adapter.in.rest.dto.RestDtos.FaultRequest;
import com.simex.twin.adapter.in.rest.dto.RestDtos.SimulationControlRequest;
import com.simex.twin.adapter.in.rest.dto.RestDtos.SimulationStatusResponse;
import com.simex.twin.adapter.in.rest.dto.RestDtos.StartRunRequest;
import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.engine.SimulationStatus;
import com.simex.twin.application.port.in.FaultInjectionUseCase;
import com.simex.twin.application.port.in.QueryStateUseCase;
import com.simex.twin.application.port.in.SimulationControlUseCase;
import com.simex.twin.domain.shared.SimDuration;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.MissingNode;

/** Corridas, control de la simulación y fallas ({@code /api/twin/sim}). */
@RestController
@RequestMapping("/api/twin/sim")
public class SimulationController {

    private static final double MS_PER_SECOND = 1000.0;

    private final SimulationControlUseCase simulation;
    private final FaultInjectionUseCase faults;
    private final QueryStateUseCase query;
    private final long timeoutMs;

    public SimulationController(
            SimulationControlUseCase simulation,
            FaultInjectionUseCase faults,
            QueryStateUseCase query,
            @Value("${twin.commands.apply-timeout-ms}") long timeoutMs) {
        this.simulation = simulation;
        this.faults = faults;
        this.query = query;
        this.timeoutMs = timeoutMs;
    }

    @GetMapping("/scenarios")
    public List<RestDtos.ScenarioSummary> scenarios() {
        return simulation.scenarios().stream()
                .map(s -> new RestDtos.ScenarioSummary(s.id(), s.description(), s.durationSimS()))
                .toList();
    }

    @PostMapping("/scenario")
    @ResponseStatus(HttpStatus.CREATED)
    public RestDtos.RunCreated startRun(@Valid @RequestBody StartRunRequest request) {
        String runId = EngineCalls.await(
                simulation.startRun(
                        Objects.requireNonNull(request.scenarioId()),
                        Optional.ofNullable(request.seed()),
                        Objects.requireNonNull(request.speedFactor()),
                        Optional.ofNullable(request.controlMode())),
                timeoutMs);
        return new RestDtos.RunCreated(runId);
    }

    @PostMapping("/control")
    public SimulationStatusResponse control(@Valid @RequestBody SimulationControlRequest request) {
        Action action = Objects.requireNonNull(request.action());
        Integer speed = request.speedFactor();
        if (action != Action.SET_SPEED && speed != null) {
            throw new IllegalArgumentException("speedFactor solo se admite con SET_SPEED");
        }
        SimulationStatus s = EngineCalls.await(
                simulation.control(action, speed == null ? OptionalInt.empty() : OptionalInt.of(speed)), timeoutMs);
        return new SimulationStatusResponse(
                s.runId().orElse(null), s.scenarioId().orElse(null), s.status().name(), s.speedFactor(), s.simTimeMs());
    }

    @GetMapping("/faults")
    public RestDtos.FaultCatalog faults() {
        List<RestDtos.CatalogEntry> catalog = faults.catalog().stream()
                .map(e -> new RestDtos.CatalogEntry(
                        e.type().name(), List.copyOf(new TreeSet<>(e.params())), e.defaults()))
                .toList();
        List<RestDtos.ActiveFault> active = query.view().snapshot().groundTruth().activeFaults().stream()
                .map(f -> new RestDtos.ActiveFault(
                        f.faultId(),
                        f.type().name(),
                        f.params(),
                        f.since().ms(),
                        f.duration()
                                .map(SimDuration::ms)
                                .map(ms -> ms / MS_PER_SECOND)
                                .orElse(null)))
                .toList();
        return new RestDtos.FaultCatalog(catalog, active);
    }

    @PostMapping("/faults")
    @ResponseStatus(HttpStatus.CREATED)
    public RestDtos.FaultCreated inject(@Valid @RequestBody FaultRequest request) {
        String faultId = EngineCalls.await(
                        faults.inject(
                                Objects.requireNonNull(request.type()),
                                ContractJson.faultParams(
                                        Objects.requireNonNullElse(request.params(), MissingNode.getInstance())),
                                Optional.ofNullable(request.durationS())),
                        timeoutMs)
                .orElseThrow(NoActiveRunException::new);
        return new RestDtos.FaultCreated(faultId);
    }

    @DeleteMapping("/faults/{faultId}")
    public ResponseEntity<Void> remove(@PathVariable String faultId) {
        boolean removed = EngineCalls.await(faults.remove(faultId), timeoutMs).orElseThrow(NoActiveRunException::new);
        if (!removed) {
            throw new NotFoundException("No hay una falla activa con id '" + faultId + "'");
        }
        return ResponseEntity.noContent().build();
    }
}
