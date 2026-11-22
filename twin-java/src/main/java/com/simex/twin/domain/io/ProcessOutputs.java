package com.simex.twin.domain.io;

import com.simex.twin.domain.shared.DutyCycle;

/**
 * Salidas que el control escribe en la planta en cada scan.
 *
 * @param ssr relé de estado sólido de la resistencia encendido
 * @param motorPwm ciclo útil del PWM del motor de la banda
 * @param discardValve electroválvula del cilindro de descarte
 * @param stampValve electroválvula del cilindro de estampado
 */
public record ProcessOutputs(boolean ssr, DutyCycle motorPwm, ValveCommand discardValve, ValveCommand stampValve) {

    /** Salidas seguras (sección 4.7): SSR apagado, motor detenido y válvulas desenergizadas. */
    public static final ProcessOutputs SAFE =
            new ProcessOutputs(false, DutyCycle.ZERO, ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED);
}
