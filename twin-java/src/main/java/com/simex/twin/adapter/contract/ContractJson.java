package com.simex.twin.adapter.contract;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.command.CommandType;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Del JSON del contrato al dominio: los {@code params} de un comando (REST y escenarios) y los de una falla. Lanza
 * {@code IllegalArgumentException} o {@code JacksonException} si no cumplen la forma del contrato.
 */
public final class ContractJson {

    private ContractJson() {}

    /** @param params los {@code params} del comando; ausente ({@code MissingNode}) equivale a {@code {}} */
    public static MachineCommand command(CommandType type, JsonNode params) {
        requireExactly(params, paramNames(type), type.name());
        return switch (type) {
            case SET_SETPOINT -> new MachineCommand.SetSetpoint(number(params, "setpointC"));
            case SET_PID -> new MachineCommand.SetPid(number(params, "kp"), number(params, "ki"), number(params, "kd"));
            case SET_BELT_SPEED -> new MachineCommand.SetBeltSpeed(number(params, "pwmDuty"));
            case SET_MODE -> new MachineCommand.SetMode(ControlMode.valueOf(text(params, "mode")));
            case START -> new MachineCommand.Start();
            case STOP -> new MachineCommand.Stop();
            case EMERGENCY_STOP -> new MachineCommand.EmergencyStop();
            case RESET -> new MachineCommand.Reset();
            case ACK_ALARM -> {
                String code = text(params, "code");
                yield new MachineCommand.AckAlarm(
                        code.equals("ALL") ? Optional.empty() : Optional.of(AlarmCode.valueOf(code)));
            }
        };
    }

    /** Números como Double y textos como String: lo que espera {@code FaultFactory}, que valida nombres y rangos. */
    public static Map<String, Object> faultParams(JsonNode json) {
        if (!json.isMissingNode() && !json.isObject()) {
            throw new IllegalArgumentException("params debe ser un objeto");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        json.properties().forEach(e -> {
            JsonNode value = e.getValue();
            if (!value.isNumber() && !value.isString()) {
                throw new IllegalArgumentException("El parámetro " + e.getKey() + " debe ser número o texto");
            }
            params.put(e.getKey(), value.isNumber() ? value.doubleValue() : value.stringValue());
        });
        return params;
    }

    private static Set<String> paramNames(CommandType type) {
        return switch (type) {
            case SET_SETPOINT -> Set.of("setpointC");
            case SET_PID -> Set.of("kp", "ki", "kd");
            case SET_BELT_SPEED -> Set.of("pwmDuty");
            case SET_MODE -> Set.of("mode");
            case ACK_ALARM -> Set.of("code");
            case START, STOP, EMERGENCY_STOP, RESET -> Set.of();
        };
    }

    private static void requireExactly(JsonNode params, Set<String> expected, String type) {
        if (!params.isMissingNode() && !params.isObject()) {
            throw new IllegalArgumentException("params debe ser un objeto");
        }
        Set<String> names = new HashSet<>(params.propertyNames());
        if (!names.equals(expected)) {
            throw new IllegalArgumentException(type + " requiere exactamente los parámetros " + expected);
        }
    }

    private static double number(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (!value.isNumber()) {
            throw new IllegalArgumentException(name + " debe ser un número");
        }
        return value.doubleValue();
    }

    private static String text(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (!value.isString()) {
            throw new IllegalArgumentException(name + " debe ser texto");
        }
        return value.stringValue();
    }
}
