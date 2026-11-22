package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.shared.DutyCycle;

/**
 * Lo que el estado pide: banda, válvulas y si el control térmico está activo.
 *
 * @param thermalActive si es falso el SSR queda apagado
 */
public record SequenceOutput(
        DutyCycle beltPwm, ValveCommand discardValve, ValveCommand stampValve, boolean thermalActive) {

    public static final SequenceOutput ALL_OFF =
            new SequenceOutput(DutyCycle.ZERO, ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED, false);

    static SequenceOutput heatingOnly() {
        return new SequenceOutput(DutyCycle.ZERO, ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED, true);
    }
}
