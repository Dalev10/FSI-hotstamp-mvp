package com.simex.twin.domain.plant.pneumatics;

import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.shared.SimDuration;

/**
 * Cilindro con válvula 5/2 monoestable: energizada extiende y desenergizada retrae por el resorte. La carrera dura
 * {@code strokeMs · strokeFactor} (la fuga la alarga); si la válvula se invierte a mitad de carrera, sigue desde donde
 * estaba. El recorrido se cuenta en ms enteros para que los fines de carrera caigan en el tick exacto.
 */
public final class PneumaticCylinder {

    private final long strokeMs;
    private ValveCommand valve = ValveCommand.DE_ENERGIZED;
    private double travelMs;
    private double effectiveStrokeMs;

    public PneumaticCylinder(long strokeMs) {
        this.strokeMs = strokeMs;
        this.effectiveStrokeMs = strokeMs;
    }

    public void command(ValveCommand command) {
        valve = command;
    }

    /** Avanza {@code dt}; devuelve {@code true} si en este tick llegó al fin de carrera extendido. */
    public boolean advance(SimDuration dt, double strokeFactor) {
        boolean wasExtended = position() == CylinderPosition.EXTENDED;
        effectiveStrokeMs = strokeMs * strokeFactor;
        travelMs = valve == ValveCommand.ENERGIZED
                ? Math.min(effectiveStrokeMs, travelMs + dt.ms())
                : Math.max(0, travelMs - dt.ms());
        return !wasExtended && position() == CylinderPosition.EXTENDED;
    }

    /** Lo que indican los fines de carrera, según la válvula y el recorrido. */
    public CylinderPosition position() {
        if (valve == ValveCommand.ENERGIZED) {
            return travelMs >= effectiveStrokeMs ? CylinderPosition.EXTENDED : CylinderPosition.EXTENDING;
        }
        return travelMs <= 0 ? CylinderPosition.RETRACTED : CylinderPosition.RETRACTING;
    }
}
