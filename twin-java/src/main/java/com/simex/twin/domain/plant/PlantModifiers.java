package com.simex.twin.domain.plant;

import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import java.util.Optional;

/**
 * Perillas que las fallas mueven sobre la planta, sin tocar el código de la física. Los factores multiplican y los
 * desplazamientos suman; {@link #neutral()} no altera nada.
 *
 * @param heaterEfficiencyFactor multiplica la eficiencia de la resistencia (degradación)
 * @param rthFactor multiplica la resistencia térmica al ambiente (frente frío)
 * @param ambientDropC baja la temperatura ambiente real (frente frío)
 * @param offsetDriftMv se suma al offset de entrada de la termocupla (deriva)
 * @param noiseSigmaFactor multiplica la desviación del ruido de medición
 * @param thermocoupleOpen termocupla cortada: el amplificador satura
 * @param frictionFactor desgaste del motor: divide la velocidad y multiplica la potencia
 * @param stampStrokeFactor fuga neumática: alarga la carrera del cilindro de estampado
 * @param discardStrokeFactor fuga neumática: alarga la carrera del cilindro de descarte
 * @param arrivalOverride ráfaga de metales: reemplaza el perfil de llegadas mientras dura
 */
public record PlantModifiers(
        double heaterEfficiencyFactor,
        double rthFactor,
        double ambientDropC,
        double offsetDriftMv,
        double noiseSigmaFactor,
        boolean thermocoupleOpen,
        double frictionFactor,
        double stampStrokeFactor,
        double discardStrokeFactor,
        Optional<ArrivalProfile> arrivalOverride) {

    private static final PlantModifiers NEUTRAL = new PlantModifiers(1, 1, 0, 0, 1, false, 1, 1, 1, Optional.empty());

    public static PlantModifiers neutral() {
        return NEUTRAL;
    }

    /** Temperatura ambiente real que ven la planta y sus sensores. */
    public double effectiveAmbientC(double ambientC) {
        return ambientC - ambientDropC;
    }

    public ArrivalProfile arrivalsOr(ArrivalProfile base) {
        return arrivalOverride.orElse(base);
    }

    public PlantModifiers withHeaterEfficiencyFactor(double value) {
        return new PlantModifiers(
                value,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withRthFactor(double value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                value,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withAmbientDropC(double value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                value,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withOffsetDriftMv(double value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                value,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withNoiseSigmaFactor(double value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                value,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withThermocoupleOpen(boolean value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                value,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withFrictionFactor(double value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                value,
                stampStrokeFactor,
                discardStrokeFactor,
                arrivalOverride);
    }

    public PlantModifiers withStrokeFactors(double stamp, double discard) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stamp,
                discard,
                arrivalOverride);
    }

    public PlantModifiers withArrivalOverride(ArrivalProfile value) {
        return new PlantModifiers(
                heaterEfficiencyFactor,
                rthFactor,
                ambientDropC,
                offsetDriftMv,
                noiseSigmaFactor,
                thermocoupleOpen,
                frictionFactor,
                stampStrokeFactor,
                discardStrokeFactor,
                Optional.of(value));
    }
}
