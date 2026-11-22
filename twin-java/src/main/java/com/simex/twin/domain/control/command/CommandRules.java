package com.simex.twin.domain.control.command;

import com.simex.twin.domain.control.command.CommandRule.Rejection;
import com.simex.twin.domain.control.command.MachineCommand.Reset;
import com.simex.twin.domain.control.command.MachineCommand.SetBeltSpeed;
import com.simex.twin.domain.control.command.MachineCommand.SetPid;
import com.simex.twin.domain.control.command.MachineCommand.SetSetpoint;
import com.simex.twin.domain.control.command.MachineCommand.Start;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.thermal.ControlMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Las reglas de la tabla 4.9, cada una en su clase, en el orden en que se evalúan. */
public final class CommandRules {

    private CommandRules() {}

    public static List<CommandRule> standard(CommandLimits limits) {
        return List.of(
                new SourceRule(),
                new StateRule(),
                new ModeRule(),
                new SetpointRangeRule(limits),
                new SetpointStepRule(limits),
                new GainRangeRule(PidGain.KP, limits),
                new GainRangeRule(PidGain.KI, limits),
                new GainRangeRule(PidGain.KD, limits),
                new GainStepRule(PidGain.KP, limits),
                new GainStepRule(PidGain.KI, limits),
                new GainStepRule(PidGain.KD, limits),
                new BeltPwmRangeRule(limits),
                new BeltPwmStepRule(limits),
                new AlarmsAcknowledgedRule(),
                new CauseClearedRule());
    }

    // Tolerancia para restas de decimales: 0.9 − 0.7 da 0.20000000000000007 y no debe superar un paso de 0.2.
    private static final double STEP_EPSILON = 1e-9;
    private static final int DETAIL_DECIMALS = 4;

    private static Optional<Rejection> reject(Rule rule, String detail) {
        return Optional.of(new Rejection(rule, detail));
    }

    private static boolean exceeds(double step, double maxStep) {
        return step > maxStep + STEP_EPSILON;
    }

    /** Número legible para el detalle: hasta 4 decimales y sin ceros de más ("2.975", "10"). */
    private static String fmt(double value) {
        return BigDecimal.valueOf(value)
                .setScale(DETAIL_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** El agente solo ajusta setpoint, PID y velocidad de banda. */
    static final class SourceRule implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            CommandType type = c.command().type();
            boolean agentAllowed = type == CommandType.SET_SETPOINT
                    || type == CommandType.SET_PID
                    || type == CommandType.SET_BELT_SPEED;
            return c.issuedBy() == IssuedCommand.Issuer.AGENT && !agentAllowed
                    ? reject(Rule.SOURCE_NOT_ALLOWED, "El agente no puede enviar " + type)
                    : Optional.empty();
        }
    }

    /** START solo desde OFF; RESET solo desde PARO o FALLA; ningún ajuste en PARO o FALLA. */
    static final class StateRule implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            MachineState state = ctx.state();
            boolean allowed = switch (c.command()) {
                case Start s -> state == MachineState.OFF;
                case Reset r -> state.isHalted();
                default -> !(c.command().type().isSetting() && state.isHalted());
            };
            return allowed
                    ? Optional.empty()
                    : reject(Rule.STATE_NOT_ALLOWED, c.command().type() + " no se acepta en " + state);
        }
    }

    /** Setpoint y ganancias no aplican en LEGACY. */
    static final class ModeRule implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            boolean pidOnly = c.command() instanceof SetSetpoint || c.command() instanceof SetPid;
            return pidOnly && ctx.mode() == ControlMode.LEGACY
                    ? reject(Rule.MODE_MISMATCH, c.command().type() + " no aplica en modo LEGACY")
                    : Optional.empty();
        }
    }

    record SetpointRangeRule(CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetSetpoint(double setpointC)
                    && !(limits.windowMinC() <= setpointC && setpointC <= limits.windowMaxC())) {
                return reject(
                        Rule.SETPOINT_RANGE,
                        "setpoint " + fmt(setpointC) + " fuera de [" + fmt(limits.windowMinC()) + ", "
                                + fmt(limits.windowMaxC()) + "]");
            }
            return Optional.empty();
        }
    }

    record SetpointStepRule(CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetSetpoint(double setpointC)
                    && exceeds(Math.abs(setpointC - ctx.setpointC()), limits.setpointMaxStepC())) {
                return reject(
                        Rule.SETPOINT_MAX_STEP,
                        "setpoint " + fmt(ctx.setpointC()) + " → " + fmt(setpointC) + ": el paso supera "
                                + fmt(limits.setpointMaxStepC()));
            }
            return Optional.empty();
        }
    }

    /** Las tres ganancias comparten la forma de sus reglas. */
    enum PidGain {
        KP("kp", Rule.KP_RANGE, Rule.KP_MAX_STEP_20PCT, SetPid::kp, CommandContext::kp, CommandLimits::kp),
        KI("ki", Rule.KI_RANGE, Rule.KI_MAX_STEP_20PCT, SetPid::ki, CommandContext::ki, CommandLimits::ki),
        KD("kd", Rule.KD_RANGE, Rule.KD_MAX_STEP_20PCT, SetPid::kd, CommandContext::kd, CommandLimits::kd);

        private final String label;
        private final Rule rangeRule;
        private final Rule stepRule;
        private final ToDoubleFunction<SetPid> requested;
        private final ToDoubleFunction<CommandContext> current;
        private final Function<CommandLimits, CommandLimits.Range> range;

        PidGain(
                String label,
                Rule rangeRule,
                Rule stepRule,
                ToDoubleFunction<SetPid> requested,
                ToDoubleFunction<CommandContext> current,
                Function<CommandLimits, CommandLimits.Range> range) {
            this.label = label;
            this.rangeRule = rangeRule;
            this.stepRule = stepRule;
            this.requested = requested;
            this.current = current;
            this.range = range;
        }
    }

    record GainRangeRule(PidGain gain, CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetPid pid) {
                double value = gain.requested.applyAsDouble(pid);
                CommandLimits.Range range = gain.range.apply(limits);
                if (!range.contains(value)) {
                    return reject(
                            gain.rangeRule,
                            gain.label + " " + fmt(value) + " fuera de [" + fmt(range.min()) + ", " + fmt(range.max())
                                    + "]");
                }
            }
            return Optional.empty();
        }
    }

    /** |Δ| no puede superar el mayor entre el 20 % del valor actual y el 5 % del rango. */
    record GainStepRule(PidGain gain, CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetPid pid) {
                double value = gain.requested.applyAsDouble(pid);
                double currentValue = gain.current.applyAsDouble(ctx);
                double maxStep = Math.max(
                        limits.pidMaxStepFraction() * Math.abs(currentValue),
                        limits.pidMinStepRangeFraction()
                                * gain.range.apply(limits).width());
                double step = Math.abs(value - currentValue);
                if (exceeds(step, maxStep)) {
                    return reject(
                            gain.stepRule,
                            gain.label + " " + fmt(currentValue) + " → " + fmt(value) + ": el paso " + fmt(step)
                                    + " supera el máximo permitido " + fmt(maxStep));
                }
            }
            return Optional.empty();
        }
    }

    record BeltPwmRangeRule(CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetBeltSpeed(double pwmDuty)
                    && !limits.beltPwm().contains(pwmDuty)) {
                return reject(
                        Rule.BELT_PWM_RANGE,
                        "PWM " + fmt(pwmDuty) + " fuera de ["
                                + fmt(limits.beltPwm().min()) + ", "
                                + fmt(limits.beltPwm().max()) + "]");
            }
            return Optional.empty();
        }
    }

    record BeltPwmStepRule(CommandLimits limits) implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            if (c.command() instanceof SetBeltSpeed(double pwmDuty)
                    && exceeds(Math.abs(pwmDuty - ctx.beltPwm()), limits.beltPwmMaxStep())) {
                return reject(
                        Rule.BELT_PWM_MAX_STEP,
                        "PWM " + fmt(ctx.beltPwm()) + " → " + fmt(pwmDuty) + ": el paso supera "
                                + fmt(limits.beltPwmMaxStep()));
            }
            return Optional.empty();
        }
    }

    static final class AlarmsAcknowledgedRule implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            return c.command() instanceof Reset && ctx.unacknowledgedCritical()
                    ? reject(Rule.ALARMS_NOT_ACKNOWLEDGED, "Hay alarmas críticas sin reconocer")
                    : Optional.empty();
        }
    }

    static final class CauseClearedRule implements CommandRule {
        @Override
        public Optional<Rejection> check(IssuedCommand c, CommandContext ctx) {
            return c.command() instanceof Reset && ctx.causeActive()
                    ? reject(Rule.CAUSE_ACTIVE, "Persiste la condición que detuvo la máquina")
                    : Optional.empty();
        }
    }
}
