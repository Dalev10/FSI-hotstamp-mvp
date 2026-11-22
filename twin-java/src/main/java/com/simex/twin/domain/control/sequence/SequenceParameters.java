package com.simex.twin.domain.control.sequence;

import com.simex.twin.domain.shared.SimDuration;

/**
 * @param heatupTimeout tiempo en CALENTANDO tras el que se levanta HEATUP_TIMEOUT
 * @param thermalWaitAlarm tiempo en ESPERA TÉRMICA tras el que se levanta THERMAL_WAIT_LONG
 * @param stampDwell permanencia del troquel extendido sobre la pieza
 */
public record SequenceParameters(SimDuration heatupTimeout, SimDuration thermalWaitAlarm, SimDuration stampDwell) {}
