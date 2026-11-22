package com.simex.twin.domain.plant.pneumatics;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Neumática de la planta.
 *
 * @param strokeMs tiempo de carrera nominal de cada cilindro
 * @param stampDwell tiempo de contacto del troquel con la pieza, en el que le retira su calor
 */
public record PneumaticParameters(long strokeMs, SimDuration stampDwell) {}
