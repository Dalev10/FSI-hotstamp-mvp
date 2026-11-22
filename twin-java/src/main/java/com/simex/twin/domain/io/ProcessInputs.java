package com.simex.twin.domain.io;

import com.simex.twin.domain.shared.DomainException;

/**
 * Entradas que el control lee de la planta en cada scan, como el registro de entradas de un PLC. La termocupla llega
 * en cuentas del ADC: convertirlas a °C es trabajo del firmware (control).
 *
 * @param tcCounts cuentas del ADC de la termocupla
 * @param ambientC lectura del sensor de ambiente
 * @param inductive sensor inductivo (NPN) activo
 * @param photoelectric sensor fotoeléctrico activo
 * @param discardPosition cilindro de descarte según sus fines de carrera
 * @param stampPosition cilindro de estampado según sus fines de carrera
 * @param beltSpeedMps velocidad de la banda (encoder ideal)
 */
public record ProcessInputs(
        int tcCounts,
        double ambientC,
        boolean inductive,
        boolean photoelectric,
        CylinderPosition discardPosition,
        CylinderPosition stampPosition,
        double beltSpeedMps) {

    public ProcessInputs {
        if (tcCounts < 0) {
            throw new DomainException("Cuentas del ADC negativas: " + tcCounts);
        }
        if (!Double.isFinite(ambientC)) {
            throw new DomainException("Lectura de ambiente inválida: " + ambientC);
        }
        if (!(beltSpeedMps >= 0 && Double.isFinite(beltSpeedMps))) {
            throw new DomainException("Velocidad de banda inválida: " + beltSpeedMps + " m/s");
        }
    }
}
