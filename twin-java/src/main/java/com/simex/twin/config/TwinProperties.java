package com.simex.twin.config;

import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.ProcessParameters;
import com.simex.twin.domain.control.command.CommandLimits;
import com.simex.twin.domain.control.safety.SafetyParameters;
import com.simex.twin.domain.control.thermal.ColdJunctionMode;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.control.thermal.FirmwareParameters;
import com.simex.twin.domain.control.thermal.LegacyParameters;
import com.simex.twin.domain.control.thermal.PidParameters;
import com.simex.twin.domain.plant.PlantParameters;
import com.simex.twin.domain.plant.conveyor.ConveyorParameters;
import com.simex.twin.domain.plant.pneumatics.PneumaticParameters;
import com.simex.twin.domain.plant.sensing.SensingParameters;
import com.simex.twin.domain.plant.thermal.ThermalParameters;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parámetros del gemelo ({@code twin.*}). Es la única fuente de números del dominio; un valor inválido impide el
 * arranque.
 */
@Validated
@ConfigurationProperties("twin")
public record TwinProperties(
        @NotNull @Valid Engine engine,
        @NotNull @Valid Telemetry telemetry,
        @NotNull @Valid Autostart autostart,
        @NotNull @Valid ProcessSettings process,
        @NotNull @Valid Thermal thermal,
        @NotNull @Valid Sensing sensing,
        @NotNull @Valid Control control,
        @NotNull @Valid Conveyor conveyor,
        @NotNull @Valid Pneumatics pneumatics,
        @NotNull @Valid Safety safety,
        @NotNull @Valid Commands commands) {

    @AssertTrue(message = "autostart.speed-factor no puede superar engine.max-speed-factor")
    public boolean isAutostartSpeedWithinMax() {
        return autostart.speedFactor() <= engine.maxSpeedFactor();
    }

    @AssertTrue(message = "safety.over-temp-trip-c debe ser mayor que process.window-max-c")
    public boolean isOverTempAboveWindow() {
        return safety.overTempTripC() > process.windowMaxC();
    }

    // Euler explícito es estable y no oscila mientras dt < τ (sección 4.1 del plan).
    @AssertTrue(message = "la constante de tiempo del troquel (rth-k-per-w · capacitance-j-per-k) debe superar el tick")
    public boolean isPlatenTimeConstantAboveTick() {
        return thermal.rthKPerW() * thermal.capacitanceJPerK() * MS_PER_SECOND > engine.tickMs();
    }

    @AssertTrue(message = "thermal.sensor-tau-ms debe superar engine.tick-ms")
    public boolean isSensorTauAboveTick() {
        return thermal.sensorTauMs() > engine.tickMs();
    }

    @AssertTrue(message = "thermal.transport-delay-ms debe ser múltiplo de engine.tick-ms")
    public boolean isTransportDelayMultipleOfTick() {
        // Con tick-ms ≤ 0 ya falla @Positive; aquí solo se evita dividir por cero.
        return engine.tickMs() > 0 && thermal.transportDelayMs() % engine.tickMs() == 0;
    }

    @AssertTrue(message = "control.pid.sample-ms debe ser múltiplo de engine.tick-ms")
    public boolean isPidSampleMultipleOfTick() {
        return engine.tickMs() > 0 && control.pid().sampleMs() % engine.tickMs() == 0;
    }

    @AssertTrue(message = "control.ssr-window-ms debe ser múltiplo de engine.tick-ms")
    public boolean isSsrWindowMultipleOfTick() {
        return engine.tickMs() > 0 && control.ssrWindowMs() % engine.tickMs() == 0;
    }

    private static final double MS_PER_SECOND = 1000.0;

    /** Parámetros del control (el firmware de la máquina). */
    public ControlParameters toControlParameters() {
        return new ControlParameters(
                sensing.toFirmwareParameters(),
                control.pid().toParameters(),
                control.legacy().toParameters(),
                new SimDuration(control.ssrWindowMs()),
                control.defaultMode(),
                new DutyCycle(conveyor.defaultPwm()),
                process.toParameters(),
                new SafetyParameters(
                        sensing.openTcCountsThreshold(),
                        safety.overTempTripC(),
                        new SimDuration(pneumatics.strokeTimeoutMs()),
                        safety.watchdogTimeoutMs()),
                new SimDuration(pneumatics.stampDwellMs()),
                commands.toLimits(process));
    }

    /** Parámetros físicos de la planta simulada. */
    public PlantParameters toPlantParameters() {
        return new PlantParameters(
                thermal.toParameters(),
                sensing.toPlantParameters(),
                conveyor.toPlantParameters(),
                pneumatics.toPlantParameters());
    }

    public record Engine(
            @Positive long tickMs,
            @Positive long wallCycleMs,
            @Positive int speedFactor,
            @Positive int maxSpeedFactor) {

        @AssertTrue(message = "speed-factor no puede superar max-speed-factor")
        public boolean isSpeedFactorWithinMax() {
            return speedFactor <= maxSpeedFactor;
        }
    }

    public record Telemetry(
            @Positive long periodSimMs,
            @NotEmpty List<String> allowedOrigins,
            @Positive int outboundQueueCapacity) {}

    /** {@code scenario} vacío significa no arrancar ninguna corrida. */
    public record Autostart(
            @NotNull String scenario, long seed, @Positive int speedFactor) {}

    public record ProcessSettings(
            double windowMinC,
            double windowMaxC,
            @PositiveOrZero double bandHysteresisC,
            @PositiveOrZero long stableMs,
            double defaultSetpointC,
            @Positive long heatupTimeoutMs,
            @Positive long thermalWaitAlarmMs) {

        @AssertTrue(message = "window-min-c debe ser menor que window-max-c")
        public boolean isWindowOrdered() {
            return windowMinC < windowMaxC;
        }

        @AssertTrue(message = "default-setpoint-c debe estar dentro de la ventana de proceso")
        public boolean isDefaultSetpointInWindow() {
            return windowMinC <= defaultSetpointC && defaultSetpointC <= windowMaxC;
        }

        public ProcessParameters toParameters() {
            return new ProcessParameters(
                    windowMinC,
                    windowMaxC,
                    bandHysteresisC,
                    new SimDuration(stableMs),
                    defaultSetpointC,
                    new SimDuration(heatupTimeoutMs),
                    new SimDuration(thermalWaitAlarmMs));
        }
    }

    public record Thermal(
            @Positive double heaterPowerW,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double heaterEfficiency,

            @Positive double capacitanceJPerK,
            @Positive double rthKPerW,
            @PositiveOrZero long transportDelayMs,
            @Positive long sensorTauMs,
            double initialTempC,
            @PositiveOrZero double pieceHeatJ) {

        public ThermalParameters toParameters() {
            return new ThermalParameters(
                    heaterPowerW,
                    heaterEfficiency,
                    capacitanceJPerK,
                    rthKPerW,
                    new SimDuration(transportDelayMs),
                    new SimDuration(sensorTauMs),
                    initialTempC,
                    pieceHeatJ);
        }
    }

    public record Sensing(
            @Positive double seebeckMvPerC,
            @Positive double amplifierGain,
            @Positive @Max(30) int adcBits, // las cuentas viajan en un int

            @Positive double adcVrefV,
            double inputOffsetMv,
            @PositiveOrZero double noiseSigmaC,
            @NotNull ColdJunctionMode coldJunction,
            double fixedColdJunctionC,
            @PositiveOrZero double ambientNoiseSigmaC,
            @Positive int openTcCountsThreshold) {

        /** Si el umbral supera la cuenta máxima del ADC, el interlock de termocupla abierta nunca dispararía. */
        @AssertTrue(message = "open-tc-counts-threshold debe ser alcanzable con adc-bits")
        public boolean isOpenTcThresholdReachable() {
            return openTcCountsThreshold <= (1L << adcBits) - 1;
        }

        /** El hardware real de la cadena de medición (planta). */
        public SensingParameters toPlantParameters() {
            return new SensingParameters(
                    seebeckMvPerC, amplifierGain, adcBits, adcVrefV, inputOffsetMv, noiseSigmaC, ambientNoiseSigmaC);
        }

        /** Las constantes que conoce el firmware (control). */
        public FirmwareParameters toFirmwareParameters() {
            return new FirmwareParameters(
                    seebeckMvPerC, amplifierGain, adcBits, adcVrefV, coldJunction, fixedColdJunctionC);
        }
    }

    public record Control(
            @NotNull ControlMode defaultMode,
            @NotNull @Valid Pid pid,
            @Positive long ssrWindowMs,
            @NotNull @Valid Legacy legacy) {

        public record Pid(
                @Positive double kp,
                @PositiveOrZero double ki,
                @PositiveOrZero double kd,
                @Positive long sampleMs,
                @Positive int derivativeFilterN) {

            public PidParameters toParameters() {
                return new PidParameters(kp, ki, kd, new SimDuration(sampleMs), derivativeFilterN);
            }
        }

        public record Legacy(@Positive long sampleMs, double thresholdC) {

            public LegacyParameters toParameters() {
                return new LegacyParameters(new SimDuration(sampleMs), thresholdC);
            }
        }
    }

    public record Conveyor(
            @Positive double lengthM,
            @Positive double inductiveAtM,
            @Positive double stationAtM,
            @Positive double exitQaAtM,
            @Positive double pieceLengthM,
            @Positive double minSpacingM,
            @Positive double nominalSpeedMps,
            @Positive double accelMps2,
            @DecimalMin("0") @DecimalMax("1") double defaultPwm,
            @Positive double motorRatedW,
            double discardFaceFromM,
            double discardFaceToM,
            @DecimalMin("0") @DecimalMax("1") double missProbBase,
            @PositiveOrZero double missProbSpeedGain,
            @Positive double missRefSpeedMps) {

        @AssertTrue(message = "debe cumplirse inductive-at-m < station-at-m < exit-qa-at-m < length-m")
        public boolean isGeometryOrdered() {
            return inductiveAtM < stationAtM && stationAtM < exitQaAtM && exitQaAtM < lengthM;
        }

        @AssertTrue(message = "discard-face-from-m debe ser menor que discard-face-to-m")
        public boolean isDiscardFaceOrdered() {
            return discardFaceFromM < discardFaceToM;
        }

        /** La banda física; {@code default-pwm} es del control. */
        public ConveyorParameters toPlantParameters() {
            return new ConveyorParameters(
                    inductiveAtM,
                    stationAtM,
                    exitQaAtM,
                    pieceLengthM,
                    minSpacingM,
                    nominalSpeedMps,
                    accelMps2,
                    motorRatedW,
                    discardFaceFromM,
                    discardFaceToM,
                    missProbBase,
                    missProbSpeedGain,
                    missRefSpeedMps);
        }
    }

    public record Pneumatics(
            @Positive long strokeMs,
            @Positive long stampDwellMs,
            @Positive long strokeTimeoutMs) {

        @AssertTrue(message = "stroke-ms debe ser menor que stroke-timeout-ms")
        public boolean isStrokeShorterThanTimeout() {
            return strokeMs < strokeTimeoutMs;
        }

        /** Los cilindros físicos; {@code stroke-timeout-ms} es del interlock (control). */
        public PneumaticParameters toPlantParameters() {
            return new PneumaticParameters(strokeMs, new SimDuration(stampDwellMs));
        }
    }

    public record Safety(double overTempTripC, @Positive long watchdogTimeoutMs) {}

    public record Commands(
            @Positive double setpointMaxStepC,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double pidMaxStepFraction,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double pidMinStepRangeFraction,

            @NotNull @Valid Range kp,
            @NotNull @Valid Range ki,
            @NotNull @Valid Range kd,
            @NotNull @Valid BeltPwm beltPwm,
            @Positive long applyTimeoutMs,
            @Positive int idempotencyCapacity) {

        /** Los límites de las reglas de comandos; el setpoint queda acotado a la ventana de proceso. */
        public CommandLimits toLimits(ProcessSettings process) {
            return new CommandLimits(
                    process.windowMinC(),
                    process.windowMaxC(),
                    setpointMaxStepC,
                    pidMaxStepFraction,
                    pidMinStepRangeFraction,
                    new CommandLimits.Range(kp.min(), kp.max()),
                    new CommandLimits.Range(ki.min(), ki.max()),
                    new CommandLimits.Range(kd.min(), kd.max()),
                    new CommandLimits.Range(beltPwm.min(), beltPwm.max()),
                    beltPwm.maxStep());
        }

        public record Range(@PositiveOrZero double min, double max) {

            @AssertTrue(message = "min no puede superar max")
            public boolean isOrdered() {
                return min <= max;
            }
        }

        public record BeltPwm(
                @Positive double min,
                @DecimalMax("1") double max,
                @Positive double maxStep) {

            @AssertTrue(message = "min no puede superar max")
            public boolean isOrdered() {
                return min <= max;
            }
        }
    }
}
