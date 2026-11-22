package com.simex.twin.domain.plant.conveyor;

/** Resultado de la inspección de salida (supuesto del MVP, sección 4.3 del plan). */
public enum ExitResult {
    /** Plástica estampada. */
    STAMPED_OK,
    /** Metal que no se descartó. */
    METAL_ESCAPED,
    /** Plástica que salió sin estampar (por ejemplo, quedó bajo la estación tras un FAULT). */
    UNSTAMPED
}
