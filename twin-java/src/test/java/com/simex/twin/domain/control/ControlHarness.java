package com.simex.twin.domain.control;

import static com.simex.twin.domain.DefaultParameters.TICK;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.CommandRules;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Arnés para probar {@link ControlLogic} con entradas a mano. Con {@code idealPlant} la banda toma la velocidad del PWM
 * y los cilindros llegan a su fin de carrera en un scan; sin él, el test fija cada entrada.
 */
public final class ControlHarness {

    public static final double AMBIENT_C = 25.0;
    private static final double FULL_SPEED_MPS = 0.25;
    private static final int MAX_COUNTS = 4095;

    public final ControlLogic control;
    public final List<ControlEvent> events = new ArrayList<>();
    public SimTime now = SimTime.ZERO;
    public long wallMs;
    public double tempC = AMBIENT_C;
    public int forcedCounts = -1;
    public boolean inductive;
    public boolean photoelectric;
    public CylinderPosition discard = CylinderPosition.RETRACTED;
    public CylinderPosition stamp = CylinderPosition.RETRACTED;
    public double speedMps;
    public boolean idealPlant = true;
    public ProcessOutputs outputs = ProcessOutputs.SAFE;
    private int commandSeq;

    public ControlHarness() {
        ControlParameters parameters = DefaultParameters.control();
        this.control = new ControlLogic(parameters, TICK, CommandRules.standard(parameters.commandLimits()), inputs());
    }

    public ProcessInputs inputs() {
        int counts = forcedCounts >= 0 ? forcedCounts : countsFor(tempC);
        return new ProcessInputs(counts, AMBIENT_C, inductive, photoelectric, discard, stamp, speedMps);
    }

    public ProcessOutputs scan() {
        outputs = control.scan(inputs(), now, wallMs, events);
        if (idealPlant) {
            speedMps = FULL_SPEED_MPS * outputs.motorPwm().value();
            discard = endOfStroke(outputs.discardValve());
            stamp = endOfStroke(outputs.stampValve());
        }
        now = now.plus(TICK);
        wallMs += TICK.ms();
        return outputs;
    }

    public void scanFor(long ms) {
        for (long t = 0; t < ms; t += TICK.ms()) {
            scan();
        }
    }

    public CommandResult command(MachineCommand command) {
        return command(Issuer.DASHBOARD, command);
    }

    public CommandResult command(Issuer issuer, MachineCommand command) {
        return control.apply(new IssuedCommand("cmd-" + ++commandSeq, issuer, Optional.empty(), command), now, events);
    }

    public MachineState state() {
        return control.status().state();
    }

    /** START, temperatura en el setpoint y 2 s estables: queda en TRANSPORTE con la banda en marcha. */
    public ControlHarness inTransport() {
        command(new MachineCommand.Start());
        tempC = DefaultParameters.DEFAULT_SETPOINT_C;
        scanFor(2_200);
        return this;
    }

    public <T extends ControlEvent> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Cuentas del ADC para una temperatura con la junta fría a 25 °C, sin ruido ni offset. */
    public static int countsFor(double tempC) {
        long counts = Math.round((tempC - AMBIENT_C) * 0.041 * 250 / 1000 / 3.3 * MAX_COUNTS);
        return Math.clamp(counts, 0, MAX_COUNTS);
    }

    private static CylinderPosition endOfStroke(ValveCommand valve) {
        return valve == ValveCommand.ENERGIZED ? CylinderPosition.EXTENDED : CylinderPosition.RETRACTED;
    }
}
