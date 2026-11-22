package com.simex.twin.domain.plant.thermal;

import com.simex.twin.domain.shared.SimDuration;

/**
 * Retardo de primer orden {@code dy/dt = (x − y)/τ}, integrado con Euler explícito. Estable y sin oscilar mientras dt <
 * τ, que la configuración valida.
 */
public final class FirstOrderLag {

    private final double tauS;
    private double value;

    public FirstOrderLag(SimDuration tau, double initialValue) {
        this.tauS = tau.seconds();
        this.value = initialValue;
    }

    public double update(double input, SimDuration dt) {
        value += (input - value) * dt.seconds() / tauS;
        return value;
    }

    public double value() {
        return value;
    }
}
