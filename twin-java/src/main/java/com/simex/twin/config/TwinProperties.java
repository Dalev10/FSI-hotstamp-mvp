package com.simex.twin.config;

import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.plant.sensing.ColdJunctionMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
            @PositiveOrZero double pieceHeatJ) {}

    public record Sensing(
            @Positive double seebeckMvPerC,
            @Positive double amplifierGain,
            @Positive int adcBits,
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
                @Positive int derivativeFilterN) {}

        public record Legacy(@Positive long sampleMs, double thresholdC) {}
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
    }

    public record Pneumatics(
            @Positive long strokeMs,
            @Positive long stampDwellMs,
            @Positive long strokeTimeoutMs) {

        @AssertTrue(message = "stroke-ms debe ser menor que stroke-timeout-ms")
        public boolean isStrokeShorterThanTimeout() {
            return strokeMs < strokeTimeoutMs;
        }
    }

    public record Safety(double overTempTripC, @Positive long watchdogTimeoutMs) {}

    public record Commands(
            @Positive double setpointMaxStepC,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double pidMaxStepFraction,

            @NotNull @Valid Range kp,
            @NotNull @Valid Range ki,
            @NotNull @Valid Range kd,
            @NotNull @Valid BeltPwm beltPwm,
            @Positive long applyTimeoutMs,
            @Positive int idempotencyCapacity) {

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
