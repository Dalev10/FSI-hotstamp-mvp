package com.simex.twin.domain.plant.conveyor;

import com.simex.twin.domain.shared.SimTime;
import java.util.List;

/** Estrategia de llegada de piezas a la cola de entrada. */
@FunctionalInterface
public interface ArrivalGenerator {

    /** Tipos de las piezas que llegan hasta {@code now} desde la llamada anterior, en orden. */
    List<PieceType> arrivalsUntil(SimTime now, ArrivalProfile profile);
}
