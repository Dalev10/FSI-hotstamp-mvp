package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.shared.SimTime;

/** Lo que miran los interlocks en cada scan. */
public record SafetyInput(
        boolean estopLatched, int tcCounts, double measuredTempC, Cylinder discard, Cylinder stamp, SimTime now) {

    /**
     * @param valve mando vigente de la válvula
     * @param commandedAt cuándo se dio ese mando
     * @param position lo que indican los fines de carrera
     */
    public record Cylinder(ValveCommand valve, SimTime commandedAt, CylinderPosition position) {

        CylinderPosition target() {
            return valve == ValveCommand.ENERGIZED ? CylinderPosition.EXTENDED : CylinderPosition.RETRACTED;
        }
    }
}
