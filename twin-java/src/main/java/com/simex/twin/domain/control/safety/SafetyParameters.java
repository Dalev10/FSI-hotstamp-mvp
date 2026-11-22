package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.shared.SimDuration;

/**
 * @param openTcCountsThreshold cuentas desde las que la termocupla se considera abierta
 * @param overTempTripC temperatura medida que dispara la sobretemperatura
 * @param strokeTimeout tiempo máximo de una carrera neumática
 * @param watchdogTimeoutMs tiempo de pared sin latidos del agente antes de aplicar parámetros seguros
 */
public record SafetyParameters(
        int openTcCountsThreshold, double overTempTripC, SimDuration strokeTimeout, long watchdogTimeoutMs) {}
