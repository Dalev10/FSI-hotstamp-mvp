package com.simex.twin.domain.plant.thermal;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Parámetros del troquel como masa concentrada (sección 4.1 del plan).
 *
 * @param heaterPowerW potencia eléctrica de la resistencia
 * @param heaterEfficiency fracción de la potencia que llega al troquel
 * @param capacitanceJPerK capacidad térmica del troquel
 * @param rthKPerW resistencia térmica al ambiente
 * @param transportDelay retardo entre el SSR y el calor en el troquel; múltiplo del tick
 * @param sensorTau constante de tiempo de la punta de la termocupla
 * @param initialTempC temperatura inicial del troquel y del sensor
 * @param pieceHeatJ calor que retira cada pieza estampada
 */
public record ThermalParameters(
        double heaterPowerW,
        double heaterEfficiency,
        double capacitanceJPerK,
        double rthKPerW,
        SimDuration transportDelay,
        SimDuration sensorTau,
        double initialTempC,
        double pieceHeatJ) {}
