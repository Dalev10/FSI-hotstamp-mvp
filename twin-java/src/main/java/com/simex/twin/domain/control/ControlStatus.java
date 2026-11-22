package com.simex.twin.domain.control;

import com.simex.twin.domain.control.alarm.ActiveAlarm;
import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.safety.Supervisor;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Lo que el control sabe tras un scan; la máquina lo publica en su snapshot.
 *
 * @param inputs última imagen de entradas leída
 * @param outputs salidas escritas en ese scan
 * @param measuredTempC temperatura medida por el firmware
 * @param tcMillivolts tensión de entrada de la termocupla reconstruida desde las cuentas
 * @param heaterDuty en PID, la duty fijada por la ventana del SSR; en LEGACY, 0 o 1
 * @param beltPwm PWM configurado para TRANSPORTE (el que se aplica cuando la banda marcha)
 * @param interlock interlock que disparó; vacío tras el RESET
 */
public record ControlStatus(
        MachineState state,
        ProcessInputs inputs,
        ProcessOutputs outputs,
        double measuredTempC,
        double tcMillivolts,
        double setpointC,
        TemperatureBand band,
        double heaterDuty,
        ControlMode mode,
        double kp,
        double ki,
        double kd,
        double beltPwm,
        Supervisor supervisor,
        OptionalLong agentHeartbeatAgeMs,
        boolean estop,
        Optional<AlarmCode> interlock,
        List<ActiveAlarm> activeAlarms,
        OptionalLong lastStampStrokeMs) {}
