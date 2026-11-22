package com.simex.twin.domain.machine;

import com.simex.twin.domain.control.ControlEvent;
import com.simex.twin.domain.control.ControlLogic;
import com.simex.twin.domain.control.ProcessParameters;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.fault.Fault;
import com.simex.twin.domain.fault.FaultInjector;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.plant.PlantEvent;
import com.simex.twin.domain.plant.SimulatedPlant;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Raíz del agregado: une el control y la planta en el ciclo de scan (sección 3.4) y traduce sus hechos a eventos de
 * dominio, contadores y energía. Usa {@link SimulatedPlant} directamente por los hechos de piezas, la verdad de terreno
 * y las potencias; las entradas y salidas pasan por {@code PlantPort}.
 */
public final class StampingMachine {

    private final ControlLogic control;
    private final SimulatedPlant plant;
    private final ProcessParameters process;
    private final FaultInjector faults;
    private final EnergyMeter energy = new EnergyMeter();
    private final List<DomainEvent> pending = new ArrayList<>();
    private Counters counters = Counters.ZERO;
    private long stampedOutOfWindow;
    private Optional<String> pieceUnderStamp = Optional.empty();
    private SimTime now = SimTime.ZERO;
    private MachineSnapshot snapshot;

    /**
     * @param faults el mismo inyector cuyos modificadores consulta la planta
     */
    public StampingMachine(
            ControlLogic control, SimulatedPlant plant, ProcessParameters process, FaultInjector faults) {
        this.control = control;
        this.plant = plant;
        this.process = process;
        this.faults = faults;
        this.snapshot = buildSnapshot();
    }

    /** Aplica un comando antes del próximo scan; sus eventos salen con ese scan. */
    public CommandResult apply(IssuedCommand command) {
        List<ControlEvent> controlEvents = new ArrayList<>();
        CommandResult result = control.apply(command, now, controlEvents);
        pending.add(
                switch (result) {
                    case CommandResult.Applied applied ->
                        new DomainEvent.CommandApplied(
                                command.commandId(), command.command().type(), command.issuedBy());
                    case CommandResult.Rejected rejected ->
                        new DomainEvent.CommandRejected(
                                command.commandId(),
                                command.command().type(),
                                command.issuedBy(),
                                rejected.rule(),
                                rejected.detail());
                });
        translate(controlEvents);
        return result;
    }

    /** Inyecta una falla desde ahora; su efecto empieza en este mismo tick. */
    public FaultInjector.ActiveFault injectFault(Fault fault, Optional<SimDuration> duration) {
        FaultInjector.ActiveFault active = faults.inject(fault, duration, now);
        pending.add(new DomainEvent.FaultInjected(
                active.faultId(),
                fault.type(),
                fault.params(),
                active.duration().map(SimDuration::seconds)));
        return active;
    }

    /** Retira una falla activa; vacío si no existe. */
    public Optional<FaultInjector.ActiveFault> removeFault(String faultId) {
        Optional<FaultInjector.ActiveFault> removed = faults.remove(faultId);
        removed.ifPresent(f ->
                pending.add(new DomainEvent.FaultCleared(f.faultId(), f.fault().type())));
        return removed;
    }

    public SimTime now() {
        return now;
    }

    public void heartbeat(long wallMs) {
        List<ControlEvent> controlEvents = new ArrayList<>();
        control.heartbeat(wallMs, controlEvents);
        translate(controlEvents);
    }

    /**
     * Un tick: leer entradas → decidir → escribir salidas → integrar la física → contadores, energía y eventos.
     *
     * @param wallNowMs tiempo de pared, solo para el watchdog
     */
    public ScanResult scan(SimDuration dt, long wallNowMs) {
        faults.update(now)
                .forEach(f -> pending.add(
                        new DomainEvent.FaultCleared(f.faultId(), f.fault().type())));
        List<ControlEvent> controlEvents = new ArrayList<>();
        ProcessOutputs outputs = control.scan(plant.readInputs(), now, wallNowMs, controlEvents);
        plant.writeOutputs(outputs);
        plant.advance(dt);
        energy.add(plant.heaterPowerW(), plant.motorPowerW(), dt);
        translate(controlEvents);
        plant.drainEvents().forEach(this::onPlantEvent);
        now = now.plus(dt);
        snapshot = buildSnapshot();
        List<DomainEvent> events = List.copyOf(pending);
        pending.clear();
        return new ScanResult(snapshot, events);
    }

    public MachineSnapshot snapshot() {
        return snapshot;
    }

    private void translate(List<ControlEvent> controlEvents) {
        for (ControlEvent event : controlEvents) {
            switch (event) {
                case ControlEvent.StateChanged e -> pending.add(new DomainEvent.StateChanged(e.from(), e.to()));
                case ControlEvent.AlarmRaised e ->
                    pending.add(new DomainEvent.AlarmRaised(e.code(), e.code().severity(), e.detail()));
                case ControlEvent.AlarmCleared e ->
                    pending.add(new DomainEvent.AlarmCleared(e.code(), e.code().severity()));
                case ControlEvent.SupervisorChanged e ->
                    pending.add(new DomainEvent.SupervisorChanged(e.from(), e.to()));
                case ControlEvent.StampCompleted e -> {
                    // El control no conoce piezas: el id sale del contacto del troquel que informó la planta.
                    pieceUnderStamp.ifPresent(
                            id -> pending.add(new DomainEvent.PieceStamped(id, e.tempC(), e.waitMs(), e.strokeMs())));
                    pieceUnderStamp = Optional.empty();
                }
            }
        }
    }

    private void onPlantEvent(PlantEvent event) {
        switch (event) {
            case PlantEvent.PieceArrived e -> counters = counters.arrived();
            case PlantEvent.PieceDiscarded e -> {
                counters = counters.discarded();
                pending.add(new DomainEvent.PieceDiscarded(e.pieceId()));
            }
            case PlantEvent.PieceExited e -> {
                switch (e.result()) {
                    case STAMPED_OK -> counters = counters.stamped();
                    case METAL_ESCAPED -> counters = counters.escaped();
                    case UNSTAMPED -> {}
                }
                pending.add(new DomainEvent.PieceExited(e.pieceId(), e.result()));
            }
            case PlantEvent.StampContact e -> {
                pieceUnderStamp = Optional.of(e.pieceId());
                if (!process.inWindow(e.platenTempC())) {
                    stampedOutOfWindow++;
                }
            }
        }
    }

    private MachineSnapshot buildSnapshot() {
        return new MachineSnapshot(
                now,
                control.status(),
                plant.queueLength(),
                counters,
                energy.heaterWh(),
                energy.motorWh(),
                new MachineSnapshot.GroundTruth(
                        plant.platenTempC(),
                        plant.realAmbientC(),
                        plant.heaterEfficiency(),
                        faults.active().stream()
                                .map(f -> new MachineSnapshot.ActiveFault(
                                        f.faultId(),
                                        f.fault().type(),
                                        f.startedAt(),
                                        f.fault().params(),
                                        f.duration()))
                                .toList(),
                        stampedOutOfWindow));
    }
}
