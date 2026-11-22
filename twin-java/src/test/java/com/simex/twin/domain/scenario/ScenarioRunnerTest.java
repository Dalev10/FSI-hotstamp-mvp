package com.simex.twin.domain.scenario;

import static com.simex.twin.domain.scenario.TestScenarios.fault;
import static com.simex.twin.domain.scenario.TestScenarios.run;
import static com.simex.twin.domain.scenario.TestScenarios.runner;
import static com.simex.twin.domain.scenario.TestScenarios.started;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.machine.ScanResult;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.shared.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioRunnerTest {

    @Test
    void actionsRunAtTheirTimeAsScenarioCommandsWithReproducibleIds() {
        List<ScanResult> first = run(runner(started(List.of())), 1);
        List<ScanResult> second = run(runner(started(List.of())), 1);

        DomainEvent.CommandApplied start =
                (DomainEvent.CommandApplied) first.getFirst().events().getFirst();
        assertThat(start.issuedBy()).isEqualTo(IssuedCommand.Issuer.SCENARIO);
        assertThat(start.commandId())
                .isEqualTo(
                        ((DomainEvent.CommandApplied) second.getFirst().events().getFirst()).commandId());
        assertThat(first.getLast().snapshot().control().state().isOperative()).isTrue();
    }

    @Test
    void faultsAreInjectedAtTheirTime() {
        ScenarioRunner runner = runner(started(List.of(fault(600, FaultType.SENSOR_NOISE, Map.of()))));
        List<ScanResult> results = run(runner, 601);

        long injectedAtMs = results.stream()
                .filter(r -> r.events().stream().anyMatch(DomainEvent.FaultInjected.class::isInstance))
                .findFirst()
                .orElseThrow()
                .snapshot()
                .simTime()
                .ms();
        assertThat(injectedAtMs).isEqualTo(600_100); // el snapshot es al final del tick que empieza en 600 s
    }

    @Test
    void theRunFinishesAtItsDuration() {
        ScenarioRunner runner = runner(started(List.of()));
        run(runner, 3600);

        assertThat(runner.finished()).isTrue();
    }

    @Test
    void theScenarioModeAndInitialTemperatureConfigureTheMachine() {
        Scenario legacyWarm = new Scenario(
                "caliente",
                "LEGACY con el troquel a 40 °C",
                42,
                60,
                ControlMode.LEGACY,
                new Scenario.InitialConditions(40, 20),
                new ArrivalProfile(10, 0.2),
                List.of(),
                List.of(),
                List.of());
        ScenarioRunner runner = runner(legacyWarm);

        assertThat(runner.machine().snapshot().control().mode()).isEqualTo(ControlMode.LEGACY);
        assertThat(runner.machine().snapshot().groundTruth().platenTempC()).isEqualTo(40.0);
        assertThat(runner.machine().snapshot().groundTruth().ambientC()).isEqualTo(20.0);
    }

    @Test
    void ambientProfileIsPiecewiseLinear() {
        AmbientProfile profile =
                new AmbientProfile(List.of(new Scenario.AmbientPoint(100, 25), new Scenario.AmbientPoint(200, 15)), 30);

        assertThat(profile.ambientCAt(0)).isEqualTo(25);
        assertThat(profile.ambientCAt(150_000)).isEqualTo(20);
        assertThat(profile.ambientCAt(500_000)).isEqualTo(15);
        assertThat(new AmbientProfile(List.of(), 30).ambientCAt(0)).isEqualTo(30);
    }

    @Test
    void invalidScenariosAreRejected() {
        assertThatThrownBy(() -> new Scenario(
                        "x",
                        "x",
                        1,
                        0,
                        ControlMode.PID,
                        new Scenario.InitialConditions(25, 25),
                        new ArrivalProfile(10, 0.2),
                        List.of(),
                        List.of(new Scenario.ScheduledAction(0, new MachineCommand.Start())),
                        List.of()))
                .isInstanceOf(DomainException.class);
    }
}
