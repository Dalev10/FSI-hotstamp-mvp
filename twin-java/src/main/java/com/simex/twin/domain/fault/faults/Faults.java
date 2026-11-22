package com.simex.twin.domain.fault.faults;

import com.simex.twin.domain.fault.Fault;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.SimDuration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Las ocho fallas de la tabla 4.10, cada una como un record inmutable. */
public final class Faults {

    private static final double SECONDS_PER_MINUTE = 60.0;

    private Faults() {}

    /** η × factor, en rampa lineal. */
    public record HeaterDegradation(double finalEfficiencyFactor, double rampS) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.HEATER_DEGRADATION;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withHeaterEfficiencyFactor(
                    m.heaterEfficiencyFactor() * Fault.ramp(1, finalEfficiencyFactor, rampS, since));
        }

        @Override
        public Map<String, Object> params() {
            return map("finalEfficiencyFactor", finalEfficiencyFactor, "rampS", rampS);
        }
    }

    /** El ambiente baja y la pérdida al ambiente sube (R_th × factor), en la misma rampa. */
    public record ColdFront(double ambientDropC, double rthFactor, double rampS) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.COLD_FRONT;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withAmbientDropC(m.ambientDropC() + Fault.ramp(0, ambientDropC, rampS, since))
                    .withRthFactor(m.rthFactor() * Fault.ramp(1, rthFactor, rampS, since));
        }

        @Override
        public Map<String, Object> params() {
            return map("ambientDropC", ambientDropC, "rthFactor", rthFactor, "rampS", rampS);
        }
    }

    /** σ del ruido de medición × factor, de inmediato. */
    public record SensorNoise(double sigmaFactor) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.SENSOR_NOISE;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withNoiseSigmaFactor(m.noiseSigmaFactor() * sigmaFactor);
        }

        @Override
        public Map<String, Object> params() {
            return map("sigmaFactor", sigmaFactor);
        }
    }

    /** El offset de entrada crece {@code driftMvPerMin} por minuto, sin tope (≈ 0,5 °C/min con 0,02 mV/min). */
    public record OffsetDrift(double driftMvPerMin) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.OFFSET_DRIFT;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withOffsetDriftMv(m.offsetDriftMv() + driftMvPerMin * since.seconds() / SECONDS_PER_MINUTE);
        }

        @Override
        public Map<String, Object> params() {
            return map("driftMvPerMin", driftMvPerMin);
        }
    }

    /** La termocupla se corta: el ADC satura. */
    public record ThermocoupleOpen() implements Fault {
        @Override
        public FaultType type() {
            return FaultType.THERMOCOUPLE_OPEN;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withThermocoupleOpen(true);
        }

        @Override
        public Map<String, Object> params() {
            return Map.of();
        }
    }

    /** Fricción del motor × factor, en rampa: menos velocidad y más potencia. */
    public record MotorWear(double frictionFactor, double rampS) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.MOTOR_WEAR;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withFrictionFactor(m.frictionFactor() * Fault.ramp(1, frictionFactor, rampS, since));
        }

        @Override
        public Map<String, Object> params() {
            return map("frictionFactor", frictionFactor, "rampS", rampS);
        }
    }

    /** Fuga neumática: la carrera del cilindro elegido dura {@code strokeFactor} veces más. */
    public record PneumaticLeak(Cylinder cylinder, double strokeFactor) implements Fault {

        public enum Cylinder {
            STAMP,
            DISCARD,
            BOTH
        }

        public static Cylinder parseCylinder(String name) {
            try {
                return Cylinder.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new DomainException("cylinder debe ser STAMP, DISCARD o BOTH: " + name);
            }
        }

        @Override
        public FaultType type() {
            return FaultType.PNEUMATIC_LEAK;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            double stamp = cylinder == Cylinder.DISCARD ? 1 : strokeFactor;
            double discard = cylinder == Cylinder.STAMP ? 1 : strokeFactor;
            return m.withStrokeFactors(m.stampStrokeFactor() * stamp, m.discardStrokeFactor() * discard);
        }

        @Override
        public Map<String, Object> params() {
            return map("cylinder", cylinder.name(), "strokeFactor", strokeFactor);
        }
    }

    /** Ráfaga de metales: reemplaza el perfil de llegadas mientras dura. */
    public record MetalBurst(double metalRatio, double meanIntervalS) implements Fault {
        @Override
        public FaultType type() {
            return FaultType.METAL_BURST;
        }

        @Override
        public PlantModifiers contribute(PlantModifiers m, SimDuration since) {
            return m.withArrivalOverride(new ArrivalProfile(meanIntervalS, metalRatio));
        }

        @Override
        public Map<String, Object> params() {
            return map("metalRatio", metalRatio, "meanIntervalS", meanIntervalS);
        }
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }
}
