package com.simex.twin.domain.fault;

import static com.simex.twin.domain.fault.FaultParams.NO_MAX;

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
                p -> new Faults.HeaterDegradation(
                        p.positive("finalEfficiencyFactor", 0.70, 1), p.nonNegative("rampS", 1800, NO_MAX)));
        register(
                FaultType.COLD_FRONT,
                Set.of("ambientDropC", "rthFactor", "rampS"),
                p -> new Faults.ColdFront(
                        p.number("ambientDropC", 10),
                        p.positive("rthFactor", 0.85, NO_MAX),
                        p.nonNegative("rampS", 600, NO_MAX)));
        register(
                FaultType.SENSOR_NOISE,
                Set.of("sigmaFactor"),
                p -> new Faults.SensorNoise(p.positive("sigmaFactor", 5, NO_MAX)));
        register(
                FaultType.OFFSET_DRIFT,
                Set.of("driftMvPerMin"),
                p -> new Faults.OffsetDrift(p.number("driftMvPerMin", 0.02)));
        register(FaultType.THERMOCOUPLE_OPEN, Set.of(), p -> new Faults.ThermocoupleOpen());
        register(
                FaultType.MOTOR_WEAR,
                Set.of("frictionFactor", "rampS"),
                p -> new Faults.MotorWear(
                        p.positive("frictionFactor", 1.30, NO_MAX), p.nonNegative("rampS", 600, NO_MAX)));
        register(
                FaultType.PNEUMATIC_LEAK,
                Set.of("cylinder", "strokeFactor"),
                p -> new Faults.PneumaticLeak(
                        Faults.PneumaticLeak.parseCylinder(p.text("cylinder")),
                        p.positive("strokeFactor", 1.5, NO_MAX)));
        register(
                FaultType.METAL_BURST,
                Set.of("metalRatio", "meanIntervalS"),
                p -> new Faults.MetalBurst(
                        p.nonNegative("metalRatio", 0.8, 1), p.positive("meanIntervalS", 5, NO_MAX)));
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
