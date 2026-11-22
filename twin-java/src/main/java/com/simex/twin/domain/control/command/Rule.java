package com.simex.twin.domain.control.command;

/** Códigos de rechazo (tabla 4.9). */
public enum Rule {
    SOURCE_NOT_ALLOWED,
    STATE_NOT_ALLOWED,
    MODE_MISMATCH,
    SETPOINT_RANGE,
    SETPOINT_MAX_STEP,
    KP_RANGE,
    KI_RANGE,
    KD_RANGE,
    KP_MAX_STEP_20PCT,
    KI_MAX_STEP_20PCT,
    KD_MAX_STEP_20PCT,
    BELT_PWM_RANGE,
    BELT_PWM_MAX_STEP,
    ALARMS_NOT_ACKNOWLEDGED,
    CAUSE_ACTIVE
}
