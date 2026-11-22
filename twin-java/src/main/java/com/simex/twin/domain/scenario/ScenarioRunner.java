package com.simex.twin.domain.scenario;

import com.simex.twin.domain.control.ControlLogic;
import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.command.CommandRules;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.fault.FaultFactory;
import com.simex.twin.domain.fault.FaultInjector;
import com.simex.twin.domain.machine.ScanResult;
import com.simex.twin.domain.machine.StampingMachine;
import com.simex.twin.domain.plant.PlantParameters;
import com.simex.twin.domain.plant.SimulatedPlant;
import com.simex.twin.domain.plant.conveyor.ExponentialArrivalGenerator;
import com.simex.twin.domain.plant.thermal.ThermalParameters;
import com.simex.twin.domain.scenario.Scenario.ScheduledAction;
import com.simex.twin.domain.scenario.Scenario.ScheduledFault;
import com.simex.twin.domain.shared.RandomStreams;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Una corrida de un escenario sobre una máquina nueva: arma planta y control con la semilla, las condiciones iniciales,
 * el modo, el ambiente y las llegadas del escenario, y en cada tick aplica las acciones y fallas que vencieron. Es
 * síncrona y no sabe de hilos: el motor de la aplicación la mueve.
 */
public final class ScenarioRunner {

    private static final double MS_PER_SECOND = 1000.0;

    private final Scenario scenario;
    private final SimDuration tick;
    private final StampingMachine machine;
    private final List<ScheduledAction> actions;
    private final List<ScheduledFault> faults;
    private int nextAction;
    private int nextFault;

    public ScenarioRunner(
            Scenario scenario,
            long seed,
            PlantParameters plantParameters,
            ControlParameters control,
            SimDuration tick) {
        this.scenario = scenario;
        this.tick = tick;
        // Estables: a igual atS se respeta el orden del archivo.
        this.actions = scenario.actions().stream()
                .sorted(Comparator.comparingLong(a -> toMs(a.atS())))
                .toList();
        this.faults = scenario.faults().stream()
                .sorted(Comparator.comparingLong(f -> toMs(f.atS())))
                .toList();

        RandomStreams streams = new RandomStreams(seed);
        FaultInjector injector = new FaultInjector(seed);
        AmbientProfile ambient = new AmbientProfile(
                scenario.ambientProfile(), scenario.initialConditions().ambientC());
        SimulatedPlant plant = new SimulatedPlant(
                withInitialTemp(plantParameters, scenario.initialConditions().platenTempC()),
                streams,
                new ExponentialArrivalGenerator(
                        streams.get(RandomStreams.Name.ARRIVALS), streams.get(RandomStreams.Name.PIECE_TYPE)),
                scenario.arrivals(),
                ambient::ambientCAt,
                injector::current,
                tick);
        ControlParameters runControl = withMode(control, scenario);
        ControlLogic controlLogic = new ControlLogic(
                runControl, tick, CommandRules.standard(runControl.commandLimits()), plant.readInputs());
        this.machine = new StampingMachine(controlLogic, plant, runControl.process(), injector);
    }

    /** Aplica lo que venció y avanza un tick. */
    public ScanResult step(long wallNowMs) {
        long nowMs = machine.now().ms();
        while (nextAction < actions.size() && toMs(actions.get(nextAction).atS()) <= nowMs) {
            machine.apply(new IssuedCommand(
                    actionCommandId(nextAction),
                    IssuedCommand.Issuer.SCENARIO,
                    Optional.empty(),
                    actions.get(nextAction).command()));
            nextAction++;
        }
        while (nextFault < faults.size() && toMs(faults.get(nextFault).atS()) <= nowMs) {
            ScheduledFault f = faults.get(nextFault++);
            machine.injectFault(
                    FaultFactory.create(f.type(), f.params()), f.durationS().map(s -> new SimDuration(toMs(s))));
        }
        return machine.scan(tick, wallNowMs);
    }

    public boolean finished() {
        return machine.now().ms() >= toMs(scenario.durationSimS());
    }

    public SimTime now() {
        return machine.now();
    }

    public StampingMachine machine() {
        return machine;
    }

    public Scenario scenario() {
        return scenario;
    }

    /** {@code commandId} reproducible de la acción {@code index} (el golden lo incluye). */
    private String actionCommandId(int index) {
        return UUID.nameUUIDFromBytes(("action:" + scenario.id() + ":" + index).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static long toMs(double seconds) {
        return Math.round(seconds * MS_PER_SECOND);
    }

    private static PlantParameters withInitialTemp(PlantParameters p, double initialTempC) {
        ThermalParameters t = p.thermal();
        return new PlantParameters(
                new ThermalParameters(
                        t.heaterPowerW(),
                        t.heaterEfficiency(),
                        t.capacitanceJPerK(),
                        t.rthKPerW(),
                        t.transportDelay(),
                        t.sensorTau(),
                        initialTempC,
                        t.pieceHeatJ()),
                p.sensing(),
                p.conveyor(),
                p.pneumatics());
    }

    private static ControlParameters withMode(ControlParameters c, Scenario scenario) {
        return new ControlParameters(
                c.firmware(),
                c.pid(),
                c.legacy(),
                c.ssrWindow(),
                scenario.controlMode(),
                c.defaultBeltPwm(),
                c.process(),
                c.safety(),
                c.stampDwell(),
                c.commandLimits());
    }
}
