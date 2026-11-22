package com.simex.twin.domain.control.command;

import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.thermal.ControlMode;

/**
 * Lo que las reglas necesitan saber de la máquina al recibir un comando.
 *
 * @param causeActive persiste la condición que llevó a PARO o FALLA
 */
public record CommandContext(
        MachineState state,
        ControlMode mode,
        double setpointC,
        double kp,
        double ki,
        double kd,
        double beltPwm,
        boolean unacknowledgedCritical,
        boolean causeActive) {}
