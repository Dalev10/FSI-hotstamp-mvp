package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.SimTime;

/**
 * Lo que un controlador térmico ve en cada scan.
 *
 * @param now instante simulado
 * @param measuredTempC temperatura medida por el firmware
 * @param operative máquina en un estado operativo (la S de LEGACY)
 * @param presence fotoeléctrico (la SP de LEGACY)
 * @param inductive inductivo (la SI de LEGACY)
 */
public record ControllerInput(
        SimTime now, double measuredTempC, boolean operative, boolean presence, boolean inductive) {}
