package com.simex.twin.domain.fault;

import com.simex.twin.domain.fault.faults.Faults;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Crea fallas a partir de {@code (tipo, parámetros)} con un registro por tipo y los valores por defecto de 4.10. */
public final class FaultFactory {

    private record Entry(Set<String> params, Function<FaultParams, Fault> create) {}

    private static final Map<FaultType, Entry> REGISTRY = new EnumMap<>(FaultType.class);

    static {
        register(
                FaultType.HEATER_DEGRADATION,
                Set.of("finalEfficiencyFactor", "rampS"),
                p -> new Faults.HeaterDegradation(p.number("finalEfficiencyFactor", 0.70), p.number("rampS", 1800)));
        register(
                FaultType.COLD_FRONT,
                Set.of("ambientDropC", "rthFactor", "rampS"),
                p -> new Faults.ColdFront(
                        p.number("ambientDropC", 10), p.number("rthFactor", 0.85), p.number("rampS", 600)));
        register(
                FaultType.SENSOR_NOISE, Set.of("sigmaFactor"), p -> new Faults.SensorNoise(p.number("sigmaFactor", 5)));
        register(
                FaultType.OFFSET_DRIFT,
                Set.of("driftMvPerMin"),
                p -> new Faults.OffsetDrift(p.number("driftMvPerMin", 0.02)));
        register(FaultType.THERMOCOUPLE_OPEN, Set.of(), p -> new Faults.ThermocoupleOpen());
        register(
                FaultType.MOTOR_WEAR,
                Set.of("frictionFactor", "rampS"),
                p -> new Faults.MotorWear(p.number("frictionFactor", 1.30), p.number("rampS", 600)));
        register(
                FaultType.PNEUMATIC_LEAK,
                Set.of("cylinder", "strokeFactor"),
                p -> new Faults.PneumaticLeak(
                        Faults.PneumaticLeak.parseCylinder(p.text("cylinder")), p.number("strokeFactor", 1.5)));
        register(
                FaultType.METAL_BURST,
                Set.of("metalRatio", "meanIntervalS"),
                p -> new Faults.MetalBurst(p.number("metalRatio", 0.8), p.number("meanIntervalS", 5)));
    }

    private FaultFactory() {}

    public static Fault create(FaultType type, Map<String, Object> params) {
        Entry entry = REGISTRY.get(type);
        return entry.create().apply(new FaultParams(type, params, entry.params()));
    }

    /** Nombres de los parámetros que acepta cada tipo (el catálogo de {@code GET /sim/faults}). */
    public static Set<String> paramNames(FaultType type) {
        return REGISTRY.get(type).params();
    }

    private static void register(FaultType type, Set<String> params, Function<FaultParams, Fault> create) {
        REGISTRY.put(type, new Entry(params, create));
    }
}
