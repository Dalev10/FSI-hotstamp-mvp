package com.simex.twin.domain.machine;

import com.simex.twin.domain.shared.SimDuration;

/** Energía eléctrica acumulada de la resistencia (sin η) y del motor, en Wh. */
public final class EnergyMeter {

    private static final double SECONDS_PER_HOUR = 3600.0;

    private double heaterWh;
    private double motorWh;

    void add(double heaterPowerW, double motorPowerW, SimDuration dt) {
        heaterWh += heaterPowerW * dt.seconds() / SECONDS_PER_HOUR;
        motorWh += motorPowerW * dt.seconds() / SECONDS_PER_HOUR;
    }

    public double heaterWh() {
        return heaterWh;
    }

    public double motorWh() {
        return motorWh;
    }
}
