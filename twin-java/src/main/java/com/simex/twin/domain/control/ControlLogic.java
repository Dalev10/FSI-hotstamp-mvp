package com.simex.twin.domain.control;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.alarm.AlarmRegistry;
import com.simex.twin.domain.control.command.CommandContext;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.CommandRule;
import com.simex.twin.domain.control.command.CommandRule.Rejection;
import com.simex.twin.domain.control.command.CommandValidator;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.safety.Interlock.Trip;
import com.simex.twin.domain.control.safety.InterlockChain;
import com.simex.twin.domain.control.safety.SafeParameters;
import com.simex.twin.domain.control.safety.SafetyInput;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.safety.Watchdog;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.sequence.SequenceInput;
import com.simex.twin.domain.control.sequence.SequenceOutput;
import com.simex.twin.domain.control.sequence.SequenceParameters;
import com.simex.twin.domain.control.sequence.Sequencer;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.control.thermal.ControllerInput;
import com.simex.twin.domain.control.thermal.ControllerState;
import com.simex.twin.domain.control.thermal.LegacyOnOffController;
import com.simex.twin.domain.control.thermal.PidController;
import com.simex.twin.domain.control.thermal.TemperatureController;
import com.simex.twin.domain.control.thermal.ThermocoupleFirmware;
import com.simex.twin.domain.control.thermal.TimeProportionalSsr;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.Percent;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.List;
import java.util.Optional;

/**
 * El firmware de la máquina en un scan (sección 3.4): firmware → interlocks → secuencia → control térmico y SSR →
 * salidas seguras si hay disparo. Los comandos se aplican antes del scan.
 */
public final class ControlLogic {

    private final ProcessParameters process;
    private final ThermocoupleFirmware firmware;
    private final PidController pid;
    private final LegacyOnOffController legacy;
    private final TimeProportionalSsr ssr;
    private final AlarmRegistry alarms = new AlarmRegistry();
    private final Sequencer sequencer;
    private final InterlockChain interlocks;
    private final CommandValidator validator;
    private final Watchdog watchdog;
    private final SafeParameters safeParameters;

    private ControlMode mode;
    private DutyCycle beltPwm;
    private boolean estopLatched;
    private Optional<AlarmCode> interlock = Optional.empty();
    private TemperatureBand band = TemperatureBand.OK;
    private Optional<SimTime> inWindowSince = Optional.empty();
    private ProcessInputs inputs;
    private ProcessOutputs outputs = ProcessOutputs.SAFE;
    private SimTime discardCommandedAt = SimTime.ZERO;
    private SimTime stampCommandedAt = SimTime.ZERO;
    private SimTime now = SimTime.ZERO;
    private double measuredTempC;
    private double heaterDuty;
    private boolean thermalWasOn;
    private long wallMs;

    public ControlLogic(
            ControlParameters parameters, SimDuration tick, List<CommandRule> rules, ProcessInputs initialInputs) {
        this.process = parameters.process();
        this.firmware = new ThermocoupleFirmware(parameters.firmware());
        this.pid = new PidController(parameters.pid(), process.defaultSetpointC());
        this.legacy = new LegacyOnOffController(parameters.legacy());
        this.ssr = new TimeProportionalSsr(parameters.ssrWindow(), tick);
        this.interlocks = InterlockChain.standard(parameters.safety());
        this.validator = new CommandValidator(rules);
        this.watchdog = new Watchdog(parameters.safety().watchdogTimeoutMs());
        this.mode = parameters.defaultMode();
        this.beltPwm = parameters.defaultBeltPwm();
        this.safeParameters = new SafeParameters(
                process.defaultSetpointC(),
                parameters.pid().kp(),
                parameters.pid().ki(),
                parameters.pid().kd(),
                parameters.defaultBeltPwm());
        this.inputs = initialInputs;
        this.measuredTempC = firmware.temperatureC(initialInputs.tcCounts(), initialInputs.ambientC());
        this.sequencer = new Sequencer(
                new SequenceParameters(process.heatupTimeout(), process.thermalWaitAlarm(), parameters.stampDwell()),
                alarms,
                new SequenceInput(SimTime.ZERO, initialInputs, measuredTempC, false, false, beltPwm));
    }

    /** Valida y aplica un comando en el instante {@code now}. */
    public CommandResult apply(IssuedCommand command, SimTime now, List<ControlEvent> events) {
        Optional<Rejection> rejection = validator.validate(command, commandContext());
        if (rejection.isPresent()) {
            return new CommandResult.Rejected(
                    command.commandId(), rejection.get().rule(), rejection.get().detail());
        }
        switch (command.command()) {
            case MachineCommand.SetSetpoint c -> pid.setSetpointC(c.setpointC());
            case MachineCommand.SetPid c -> pid.setGains(c.kp(), c.ki(), c.kd());
            case MachineCommand.SetBeltSpeed c -> beltPwm = new DutyCycle(c.pwmDuty());
            case MachineCommand.SetMode c -> switchMode(c.mode());
            case MachineCommand.Start c -> sequencer.start(now, events);
            case MachineCommand.Stop c -> sequencer.stop(now, events);
            case MachineCommand.EmergencyStop c -> estopLatched = true;
            case MachineCommand.Reset c -> reset(now, events);
            case MachineCommand.AckAlarm c -> alarms.acknowledge(c.code());
        }
        return new CommandResult.Applied(command.commandId(), now);
    }

    /** Latido del agente, con su instante de pared. */
    public void heartbeat(long wallMs, List<ControlEvent> events) {
        switch (watchdog.heartbeat(wallMs)) {
            case ARMED -> events.add(new ControlEvent.SupervisorChanged(Supervisor.NONE, Supervisor.AGENT));
            case RECOVERED -> {
                alarms.clear(AlarmCode.WATCHDOG_TIMEOUT, events);
                events.add(new ControlEvent.SupervisorChanged(Supervisor.NONE, Supervisor.AGENT));
            }
            default -> {}
        }
    }

    public ProcessOutputs scan(ProcessInputs in, SimTime now, long wallMs, List<ControlEvent> events) {
        this.now = now;
        this.inputs = in;
        this.wallMs = wallMs;
        measuredTempC = firmware.temperatureC(in.tcCounts(), in.ambientC());
        band = band.next(measuredTempC, process);
        boolean inWindow = process.inWindow(measuredTempC);
        inWindowSince = inWindow ? Optional.of(inWindowSince.orElse(now)) : Optional.empty();
        boolean inWindowStable = inWindowSince
                .map(since -> now.minus(since).ms() >= process.stable().ms())
                .orElse(false);
        checkWatchdog(events);

        Optional<Trip> trip = interlocks.evaluate(safetyInput());
        SequenceOutput requested;
        if (trip.isPresent()) {
            interlock = Optional.of(trip.get().alarm());
            alarms.raise(trip.get().alarm(), now, trip.get().detail(), events);
            sequencer.trip(trip.get().target(), now, events);
            requested = SequenceOutput.ALL_OFF;
        } else {
            requested = sequencer.scan(
                    new SequenceInput(now, in, measuredTempC, inWindow, inWindowStable, beltPwm), events);
        }
        MachineState state = sequencer.state();
        updateTemperatureAlarms(state, events);

        boolean thermalOn = requested.thermalActive() && !state.isHalted();
        boolean ssrOn = thermalOutput(thermalOn, state, in);
        ProcessOutputs next = state.isHalted()
                ? ProcessOutputs.SAFE
                : new ProcessOutputs(ssrOn, requested.beltPwm(), requested.discardValve(), requested.stampValve());
        if (next.discardValve() != outputs.discardValve()) {
            discardCommandedAt = now;
        }
        if (next.stampValve() != outputs.stampValve()) {
            stampCommandedAt = now;
        }
        outputs = next;
        return next;
    }

    public ControlStatus status() {
        return new ControlStatus(
                sequencer.state(),
                inputs,
                outputs,
                measuredTempC,
                firmware.inputMillivolts(inputs.tcCounts()),
                pid.setpointC(),
                band,
                heaterDuty,
                mode,
                pid.kp(),
                pid.ki(),
                pid.kd(),
                beltPwm.value(),
                watchdog.supervisor(),
                watchdog.heartbeatAgeMs(wallMs),
                estopLatched,
                interlock,
                alarms.active(),
                sequencer.lastStampStrokeMs());
    }

    /**
     * El controlador activo solo se calcula con el térmico activo (si no, la integral crecería sin que la salida llegue
     * al SSR) y arranca en frío al reactivarse. PID pasa por la ventana del SSR; LEGACY lo maneja directo, como el
     * sketch del ITO 2.
     */
    private boolean thermalOutput(boolean thermalOn, MachineState state, ProcessInputs in) {
        if (thermalOn && !thermalWasOn) {
            pid.restart();
            legacy.reset(new ControllerState(new Percent(0)));
        }
        thermalWasOn = thermalOn;
        Percent output = thermalOn
                ? activeController()
                        .compute(new ControllerInput(
                                now, measuredTempC, state.isOperative(), in.photoelectric(), in.inductive()))
                : new Percent(0);
        if (mode == ControlMode.PID) {
            boolean on = ssr.update(output.toDutyCycle());
            heaterDuty = ssr.latchedDuty().value();
            return on;
        }
        ssr.update(DutyCycle.ZERO);
        boolean on = output.value() >= Percent.MAX;
        heaterDuty = on ? 1 : 0;
        return on;
    }

    private void checkWatchdog(List<ControlEvent> events) {
        if (watchdog.check(wallMs) == Watchdog.Change.TIMED_OUT) {
            pid.setSetpointC(safeParameters.setpointC());
            pid.setGains(safeParameters.kp(), safeParameters.ki(), safeParameters.kd());
            beltPwm = safeParameters.beltPwm();
            alarms.raise(AlarmCode.WATCHDOG_TIMEOUT, now, Optional.empty(), events);
            events.add(new ControlEvent.SupervisorChanged(Supervisor.AGENT, Supervisor.NONE));
        }
    }

    private void updateTemperatureAlarms(MachineState state, List<ControlEvent> events) {
        boolean evaluated = state.hasTemperatureAlarms();
        if (evaluated && band == TemperatureBand.LOW) {
            alarms.raise(AlarmCode.TEMP_LOW, now, Optional.empty(), events);
        } else {
            alarms.clear(AlarmCode.TEMP_LOW, events);
        }
        if (evaluated && band == TemperatureBand.HIGH) {
            alarms.raise(AlarmCode.TEMP_HIGH, now, Optional.empty(), events);
        } else {
            alarms.clear(AlarmCode.TEMP_HIGH, events);
        }
    }

    private void switchMode(ControlMode newMode) {
        if (newMode != mode) {
            ControllerState current = activeController().snapshot();
            mode = newMode;
            activeController().reset(current);
        }
    }

    private void reset(SimTime now, List<ControlEvent> events) {
        estopLatched = false;
        interlock = Optional.empty();
        alarms.clearLatched(events);
        sequencer.reset(now, events);
    }

    private TemperatureController activeController() {
        return mode == ControlMode.PID ? pid : legacy;
    }

    private CommandContext commandContext() {
        boolean causeActive = interlock
                .map(alarm -> interlocks.causeActive(alarm, safetyInput()))
                .orElse(false);
        return new CommandContext(
                sequencer.state(),
                mode,
                pid.setpointC(),
                pid.kp(),
                pid.ki(),
                pid.kd(),
                beltPwm.value(),
                alarms.hasUnacknowledgedCritical(),
                causeActive);
    }

    private SafetyInput safetyInput() {
        return new SafetyInput(
                estopLatched,
                inputs.tcCounts(),
                measuredTempC,
                new SafetyInput.Cylinder(outputs.discardValve(), discardCommandedAt, inputs.discardPosition()),
                new SafetyInput.Cylinder(outputs.stampValve(), stampCommandedAt, inputs.stampPosition()),
                now);
    }
}
