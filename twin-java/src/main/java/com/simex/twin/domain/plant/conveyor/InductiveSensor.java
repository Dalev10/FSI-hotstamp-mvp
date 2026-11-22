package com.simex.twin.domain.plant.conveyor;

import com.simex.twin.domain.shared.RandomStream;

/**
 * Sensor inductivo NPN: vale 1 si un metal se superpone a su posición y no fue sorteado como "no detectado". El sorteo
 * se hace una vez por pieza metálica, cuando su frente cruza el sensor, con
 * {@code p_miss(v) = base + gain · max(0, v − v_ref) / v_ref} (tiempo de permanencia y frecuencia de conmutación).
 */
public final class InductiveSensor {

    private final double positionM;
    private final double missProbBase;
    private final double missProbSpeedGain;
    private final double missRefSpeedMps;
    private final RandomStream missDraws;

    public InductiveSensor(ConveyorParameters parameters, RandomStream missDraws) {
        this.positionM = parameters.inductiveAtM();
        this.missProbBase = parameters.missProbBase();
        this.missProbSpeedGain = parameters.missProbSpeedGain();
        this.missRefSpeedMps = parameters.missRefSpeedMps();
        this.missDraws = missDraws;
    }

    public double missProbability(double speedMps) {
        return missProbBase + missProbSpeedGain * Math.max(0, speedMps - missRefSpeedMps) / missRefSpeedMps;
    }

    /** Sortea la no detección si el frente de un metal cruzó el sensor en este tick. */
    void onAdvance(Piece piece, double previousFrontM, double speedMps) {
        if (piece.isMetal() && previousFrontM < positionM && piece.frontM() >= positionM) {
            piece.setInductiveMissed(missDraws.nextDouble() < missProbability(speedMps));
        }
    }

    boolean detects(Piece piece) {
        return piece.isMetal() && !piece.inductiveMissed() && piece.overlaps(positionM);
    }
}
