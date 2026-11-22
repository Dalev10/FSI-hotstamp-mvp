package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Ganancias iniciales y muestreo del PID.
 *
 * @param kp %/°C
 * @param ki %/(°C·s)
 * @param kd %·s/°C
 * @param sample período de muestreo; múltiplo del tick
 * @param derivativeFilterN el filtro de la derivada tiene {@code τ_f = (kd/kp)/N}
 */
public record PidParameters(double kp, double ki, double kd, SimDuration sample, double derivativeFilterN) {}
