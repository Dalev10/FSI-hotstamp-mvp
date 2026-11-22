package com.simex.twin.domain.machine;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.control.ControlLogic;
import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.alarm.Severity;
import com.simex.twin.domain.control.command.CommandRules;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.command.Rule;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.plant.SimulatedPlant;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.plant.conveyor.ExitResult;
import com.simex.twin.domain.plant.conveyor.ExponentialArrivalGenerator;
import com.simex.twin.domain.shared.RandomStreams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StampingMachineTest {

    private static final int ONE_HOUR_TICKS = 36_000;

    /** Criterio de cierre de la T6: 1 h sin fallas con piezas que fluyen y contadores coherentes. */
    @Test
    void oneHourWithoutFaultsKeepsPiecesFlowingAndCountersCoherent() {
        StampingMachine machine = machine();
        machine.apply(command(Issuer.DASHBOARD, new MachineCommand.Start()));
        List<DomainEvent> events = new ArrayList<>();
        for (int tick = 0; tick < ONE_HOUR_TICKS; tick++) {
            events.addAll(machine.scan(TICK, tick * TICK.ms()).events());
        }
        MachineSnapshot end = machine.snapshot();
        Counters counters = end.counters();
        List<DomainEvent.PieceStamped> stamped = eventsOf(events, DomainEvent.PieceStamped.class);
        List<DomainEvent.PieceExited> exited = eventsOf(events, DomainEvent.PieceExited.class);

        // Piezas que fluyen.
        assertThat(counters.piecesArrived()).isGreaterThan(300);
        assertThat(counters.plasticStamped()).isGreaterThan(200);
        assertThat(counters.metalDiscarded()).isGreaterThan(40);
        // Contadores coherentes: lo que llegó salió, se descartó, sigue en la banda o en la cola.
        long accounted = counters.metalDiscarded() + exited.size() + end.queueLength();
        assertThat(counters.piecesArrived() - accounted).isBetween(0L, 14L); // caben 14 en la banda
        assertThat(counters.plasticStamped() + counters.metalEscaped()).isEqualTo(exited.size());
        assertThat(exited).noneMatch(e -> e.result() == ExitResult.UNSTAMPED);
        // Cero estampados fuera de la ventana medida, ni de la real.
        assertThat(stamped).allSatisfy(s -> assertThat(s.tempC()).isBetween(50.0, 75.0));
        assertThat(end.groundTruth().stampedOutOfWindow()).isZero();
        // Toda pieza estampada sale como STAMPED_OK (o es la que está en la banda al final).
        Set<String> exitedOk = new HashSet<>();
        exited.stream().filter(e -> e.result() == ExitResult.STAMPED_OK).forEach(e -> exitedOk.add(e.pieceId()));
        assertThat(stamped.stream().filter(s -> !exitedOk.contains(s.pieceId())).count())
                .isLessThanOrEqualTo(3);
        // Sin paros ni fallas.
        assertThat(eventsOf(events, DomainEvent.StateChanged.class)).noneMatch(e -> e.to().isHalted());
        assertThat(eventsOf(events, DomainEvent.AlarmRaised.class)).noneMatch(e -> e.severity() == Severity.CRITICAL);
        assertThat(end.heaterWh()).isPositive();
        assertThat(end.motorWh()).isPositive();
    }

    @Test
    void commandEventsComeFirstInTheNextScan() {
        StampingMachine machine = machine();
        machine.apply(command(Issuer.DASHBOARD, new MachineCommand.Start()));

        List<DomainEvent> events = machine.scan(TICK, 0).events();

        assertThat(events.getFirst()).isInstanceOf(DomainEvent.CommandApplied.class);
        assertThat(events.get(1)).isEqualTo(new DomainEvent.StateChanged(MachineState.OFF, MachineState.HEATING));
    }

    @Test
    void aRejectedCommandIsReportedWithItsRule() {
        StampingMachine machine = machine();
        machine.apply(command(Issuer.AGENT, new MachineCommand.Start()));

        assertThat(machine.scan(TICK, 0).events())
                .first()
                .isInstanceOfSatisfying(
                        DomainEvent.CommandRejected.class,
                        e -> assertThat(e.rule()).isEqualTo(Rule.SOURCE_NOT_ALLOWED));
    }

    @Test
    void alarmAndSupervisorFactsBecomeDomainEvents() {
        StampingMachine machine = machine();
        machine.heartbeat(0);
        machine.apply(command(Issuer.DASHBOARD, new MachineCommand.Start()));
        List<DomainEvent> events = new ArrayList<>();
        long wallMs = 0;
        for (int tick = 0; tick < 110; tick++, wallMs += TICK.ms()) { // el agente calla 11 s
            events.addAll(machine.scan(TICK, wallMs).events());
        }
        machine.heartbeat(wallMs);
        events.addAll(machine.scan(TICK, wallMs).events());

        assertThat(eventsOf(events, DomainEvent.SupervisorChanged.class))
                .containsExactly(
                        new DomainEvent.SupervisorChanged(Supervisor.NONE, Supervisor.AGENT),
                        new DomainEvent.SupervisorChanged(Supervisor.AGENT, Supervisor.NONE),
                        new DomainEvent.SupervisorChanged(Supervisor.NONE, Supervisor.AGENT));
        assertThat(eventsOf(events, DomainEvent.AlarmRaised.class))
                .contains(new DomainEvent.AlarmRaised(AlarmCode.WATCHDOG_TIMEOUT, Severity.WARNING, Optional.empty()));
        assertThat(eventsOf(events, DomainEvent.AlarmCleared.class))
                .contains(new DomainEvent.AlarmCleared(AlarmCode.WATCHDOG_TIMEOUT, Severity.WARNING));
    }

    @Test
    void theSnapshotBeforeAnyScanIsAColdOffMachine() {
        MachineSnapshot snapshot = machine().snapshot();

        assertThat(snapshot.control().state()).isEqualTo(MachineState.OFF);
        assertThat(snapshot.counters()).isEqualTo(Counters.ZERO);
        assertThat(snapshot.groundTruth().platenTempC()).isEqualTo(25.0);
        assertThat(snapshot.groundTruth().heaterEfficiency()).isEqualTo(0.95);
    }

    private static StampingMachine machine() {
        RandomStreams streams = new RandomStreams(42);
        SimulatedPlant plant = new SimulatedPlant(
                DefaultParameters.plant(),
                streams,
                new ExponentialArrivalGenerator(
                        streams.get(RandomStreams.Name.ARRIVALS), streams.get(RandomStreams.Name.PIECE_TYPE)),
                new ArrivalProfile(10, 0.2),
                ms -> 25.0,
                PlantModifiers::neutral,
                TICK);
        ControlParameters parameters = DefaultParameters.control();
        ControlLogic control = new ControlLogic(
                parameters, TICK, CommandRules.standard(parameters.commandLimits()), plant.readInputs());
        return new StampingMachine(control, plant, parameters.process());
    }

    private static IssuedCommand command(Issuer issuer, MachineCommand command) {
        return new IssuedCommand("7c9e6679-7425-40de-944b-e07fc1f90ae7", issuer, Optional.empty(), command);
    }

    private static <T extends DomainEvent> List<T> eventsOf(List<DomainEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
