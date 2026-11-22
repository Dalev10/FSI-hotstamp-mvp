package com.simex.twin.domain.io;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Planta falsa e ideal para probar el control sin física: los cilindros llegan al fin de carrera en un tick, la banda
 * toma su velocidad al instante y la termocupla sube o baja una cuenta por tick según el SSR. Los sensores de piezas
 * se fijan a mano.
 */
public final class FakePlant implements PlantPort {

    private static final double FULL_SPEED_MPS = 0.25;
    private static final double AMBIENT_C = 25.0;

    private ProcessOutputs outputs = ProcessOutputs.SAFE;
    private int tcCounts;
    private boolean inductive;
    private boolean photoelectric;
    private CylinderPosition discard = CylinderPosition.RETRACTED;
    private CylinderPosition stamp = CylinderPosition.RETRACTED;
    private double speedMps;

    @Override
    public ProcessInputs readInputs() {
        return new ProcessInputs(tcCounts, AMBIENT_C, inductive, photoelectric, discard, stamp, speedMps);
    }

    @Override
    public void writeOutputs(ProcessOutputs outputs) {
        this.outputs = outputs;
    }

    @Override
    public void advance(SimDuration dt) {
        discard = endOfStroke(outputs.discardValve());
        stamp = endOfStroke(outputs.stampValve());
        speedMps = FULL_SPEED_MPS * outputs.motorPwm().value();
        tcCounts = Math.max(0, tcCounts + (outputs.ssr() ? 1 : -1));
    }

    public void setTcCounts(int counts) {
        tcCounts = counts;
    }

    public void setInductive(boolean active) {
        inductive = active;
    }

    public void setPhotoelectric(boolean active) {
        photoelectric = active;
    }

    private static CylinderPosition endOfStroke(ValveCommand valve) {
        return valve == ValveCommand.ENERGIZED ? CylinderPosition.EXTENDED : CylinderPosition.RETRACTED;
    }
}
