package com.simex.twin.domain.control.thermal;

/** Estrategia térmica activa: réplica de la lógica del ITO 2 o PID con SSR. */
public enum ControlMode {
    LEGACY,
    PID
}
