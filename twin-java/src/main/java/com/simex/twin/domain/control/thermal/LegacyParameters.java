package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.SimDuration;

/**
 * @param sample período de evaluación ({@code delay(1000) + delay(10)} del sketch)
 * @param thresholdC umbral de "temperatura alcanzada" (T)
 */
public record LegacyParameters(SimDuration sample, double thresholdC) {}
