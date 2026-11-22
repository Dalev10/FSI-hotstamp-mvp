package com.simex.twin.domain.control;

import com.simex.twin.domain.control.command.CommandLimits;
import com.simex.twin.domain.control.safety.SafetyParameters;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.control.thermal.FirmwareParameters;
import com.simex.twin.domain.control.thermal.LegacyParameters;
import com.simex.twin.domain.control.thermal.PidParameters;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimDuration;

/** Todo lo que configura el control (lo que en la máquina real sería el firmware del PLC). */
public record ControlParameters(
        FirmwareParameters firmware,
        PidParameters pid,
        LegacyParameters legacy,
        SimDuration ssrWindow,
        ControlMode defaultMode,
        DutyCycle defaultBeltPwm,
        ProcessParameters process,
        SafetyParameters safety,
        SimDuration stampDwell,
        CommandLimits commandLimits) {}
