package com.simex.twin.domain.plant;

import com.simex.twin.domain.plant.conveyor.ExitResult;
import com.simex.twin.domain.plant.conveyor.PieceType;

/**
 * Hechos físicos de la planta simulada sobre las piezas. No pasan por {@code PlantPort}: unas E/S reales no conocen
 * piezas. La máquina los traduce a eventos de dominio y contadores.
 */
public sealed interface PlantEvent {

    record PieceArrived(String pieceId, PieceType type) implements PlantEvent {}

    record PieceDiscarded(String pieceId) implements PlantEvent {}

    record PieceExited(String pieceId, ExitResult result) implements PlantEvent {}

    /** El troquel tocó la pieza; lleva la temperatura real del troquel en ese momento. */
    record StampContact(String pieceId, double platenTempC) implements PlantEvent {}
}
