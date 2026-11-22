package com.simex.twin.domain.plant.conveyor;

/**
 * Geometría de la banda, motor y sensor inductivo (sección 4.3 del plan). Posiciones en metros desde la entrada.
 *
 * @param inductiveAtM sensor inductivo y pistón de descarte
 * @param stationAtM sensor fotoeléctrico y estación de estampado
 * @param exitQaAtM inspección de salida
 * @param pieceLengthM largo de cada pieza
 * @param minSpacingM tramo libre que necesita una pieza para entrar
 * @param nominalSpeedMps velocidad con PWM 1 y sin desgaste
 * @param accelMps2 aceleración máxima del motor, también al frenar
 * @param motorRatedW potencia del motor con PWM 1
 * @param discardFaceFromM inicio de la cara del pistón, relativo al sensor inductivo
 * @param discardFaceToM fin de la cara del pistón, relativo al sensor inductivo
 * @param missProbBase probabilidad base de que el inductivo no detecte un metal
 * @param missProbSpeedGain aumento de esa probabilidad con la velocidad
 * @param missRefSpeedMps velocidad desde la que la probabilidad empieza a crecer
 */
public record ConveyorParameters(
        double inductiveAtM,
        double stationAtM,
        double exitQaAtM,
        double pieceLengthM,
        double minSpacingM,
        double nominalSpeedMps,
        double accelMps2,
        double motorRatedW,
        double discardFaceFromM,
        double discardFaceToM,
        double missProbBase,
        double missProbSpeedGain,
        double missRefSpeedMps) {}
