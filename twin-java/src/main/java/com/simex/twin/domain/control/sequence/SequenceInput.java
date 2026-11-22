package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimTime;

/**
 * Lo que la secuencia ve en un scan.
 *
 * @param inWindow temperatura medida dentro de la ventana de proceso
 * @param inWindowStable dentro de la ventana durante {@code stable-ms} seguidos
 * @param beltPwm PWM configurado para TRANSPORTE
 */
public record SequenceInput(
        SimTime now,
        ProcessInputs inputs,
        double measuredTempC,
        boolean inWindow,
        boolean inWindowStable,
        DutyCycle beltPwm) {}
