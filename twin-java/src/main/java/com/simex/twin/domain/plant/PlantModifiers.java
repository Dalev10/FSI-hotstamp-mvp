package com.simex.twin.domain.plant;

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
 */
public record PlantModifiers(
        double heaterEfficiencyFactor,
        double rthFactor,
        double ambientDropC,
        double offsetDriftMv,
        double noiseSigmaFactor,
        boolean thermocoupleOpen) {

    private static final PlantModifiers NEUTRAL = new PlantModifiers(1, 1, 0, 0, 1, false);

    public static PlantModifiers neutral() {
        return NEUTRAL;
    }

    /** Temperatura ambiente real que ven la planta y sus sensores. */
    public double effectiveAmbientC(double ambientC) {
        return ambientC - ambientDropC;
    }
}
