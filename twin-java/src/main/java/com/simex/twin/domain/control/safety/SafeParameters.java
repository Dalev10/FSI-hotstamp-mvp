package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.shared.DutyCycle;

/** Lo que el watchdog impone si el agente deja de latir: setpoint, ganancias y PWM por defecto. */
public record SafeParameters(double setpointC, double kp, double ki, double kd, DutyCycle beltPwm) {}
