package com.simex.twin.domain.plant.conveyor;

/** Sensor fotoeléctrico de la estación: vale 1 si cualquier pieza se superpone a su posición. */
public record PhotoelectricSensor(double positionM) {

    boolean detects(Piece piece) {
        return piece.overlaps(positionM);
    }
}
