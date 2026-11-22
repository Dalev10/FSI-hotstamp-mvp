package com.simex.twin.domain.plant.conveyor;

/** Inspección de salida: una pieza sale cuando su centro pasa la posición de inspección. */
public record ExitInspection(double positionM) {

    boolean hasExited(Piece piece) {
        return piece.centerM() >= positionM;
    }

    ExitResult inspect(Piece piece) {
        if (piece.isMetal()) {
            return ExitResult.METAL_ESCAPED;
        }
        return piece.stamped() ? ExitResult.STAMPED_OK : ExitResult.UNSTAMPED;
    }
}
