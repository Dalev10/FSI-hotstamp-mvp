package com.simex.twin.domain.control.command;

/**
 * Límites que hacen cumplir las reglas de comandos.
 *
 * @param windowMinC el setpoint no puede quedar debajo
 * @param windowMaxC el setpoint no puede quedar encima
 * @param setpointMaxStepC máximo cambio de setpoint por comando
 * @param pidMaxStepFraction el paso de una ganancia puede llegar a esta fracción de su valor actual…
 * @param pidMinStepRangeFraction …o, si es mayor, a esta fracción de su rango
 */
public record CommandLimits(
        double windowMinC,
        double windowMaxC,
        double setpointMaxStepC,
        double pidMaxStepFraction,
        double pidMinStepRangeFraction,
        Range kp,
        Range ki,
        Range kd,
        Range beltPwm,
        double beltPwmMaxStep) {

    public record Range(double min, double max) {

        boolean contains(double value) {
            return min <= value && value <= max;
        }

        double width() {
            return max - min;
        }
    }
}
