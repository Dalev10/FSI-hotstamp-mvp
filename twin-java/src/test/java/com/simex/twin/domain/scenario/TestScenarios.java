package com.simex.twin.domain.scenario;

import static com.simex.twin.domain.DefaultParameters.TICK;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.machine.ScanResult;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Escenarios armados en las pruebas y un atajo para correrlos. */
final class TestScenarios {

    private TestScenarios() {}

    static Scenario started(List<Scenario.ScheduledFault> faults) {
        return scenario(List.of(new Scenario.ScheduledAction(0, new MachineCommand.Start())), faults);
    }

    static Scenario idle(List<Scenario.ScheduledFault> faults) {
        return scenario(List.of(), faults);
    }

    static Scenario.ScheduledFault fault(double atS, FaultType type, Map<String, Object> params) {
        return new Scenario.ScheduledFault(atS, type, params, Optional.empty());
    }

    static ScenarioRunner runner(Scenario scenario) {
        return new ScenarioRunner(
                scenario, scenario.seed(), DefaultParameters.plant(), DefaultParameters.control(), TICK);
    }

    /** Corre hasta {@code untilS} y devuelve cada resultado; {@code each} recibe los que caen desde {@code fromS}. */
    static List<ScanResult> run(ScenarioRunner runner, double untilS) {
        List<ScanResult> results = new ArrayList<>();
        run(runner, 0, untilS, results::add);
        return results;
    }

    static void run(ScenarioRunner runner, double fromS, double untilS, Consumer<ScanResult> each) {
        while (runner.now().ms() < untilS * 1000) {
            boolean observed = runner.now().ms() >= fromS * 1000;
            ScanResult result = runner.step(runner.now().ms());
            if (observed) {
                each.accept(result);
            }
        }
    }

    private static Scenario scenario(List<Scenario.ScheduledAction> actions, List<Scenario.ScheduledFault> faults) {
        return new Scenario(
                "prueba",
                "Escenario de prueba",
                42,
                3600,
                ControlMode.PID,
                new Scenario.InitialConditions(25, 25),
                new ArrivalProfile(10, 0.2),
                List.of(),
                actions,
                faults);
    }
}
