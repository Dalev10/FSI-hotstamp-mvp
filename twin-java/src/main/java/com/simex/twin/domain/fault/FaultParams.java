package com.simex.twin.domain.fault;

import com.simex.twin.domain.shared.DomainException;
import java.util.Map;
import java.util.Set;

/** Lectura validada de los parámetros de una falla: un nombre desconocido o un tipo incorrecto es un error. */
public final class FaultParams {

    private final FaultType type;
    private final Map<String, Object> values;

    FaultParams(FaultType type, Map<String, Object> values, Set<String> allowed) {
        for (String name : values.keySet()) {
            if (!allowed.contains(name)) {
                throw new DomainException("Parámetro desconocido para " + type + ": " + name);
            }
        }
        this.type = type;
        this.values = values;
    }

    public double number(String name, double defaultValue) {
        Object value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new DomainException(type + "." + name + " debe ser un número: " + value);
    }

    public String text(String name) {
        if (values.get(name) instanceof String text) {
            return text;
        }
        throw new DomainException(type + "." + name + " es obligatorio y debe ser texto");
    }
}
