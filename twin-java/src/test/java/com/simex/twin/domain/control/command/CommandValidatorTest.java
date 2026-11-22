package com.simex.twin.domain.control.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand.Reset;
import com.simex.twin.domain.control.command.MachineCommand.SetBeltSpeed;
import com.simex.twin.domain.control.command.MachineCommand.SetPid;
import com.simex.twin.domain.control.command.MachineCommand.SetSetpoint;
import com.simex.twin.domain.control.command.MachineCommand.Start;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.control.thermal.ControlMode;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Cada código de la tabla 4.9 con un caso que lo viola y otro, cercano, que pasa. */
class CommandValidatorTest {

    /** TRANSPORTE en PID, setpoint 62,5, kp 10 / ki 0,1 / kd 0, PWM 0,7, sin alarmas. */
    private static final CommandContext RUNNING =
            new CommandContext(MachineState.TRANSPORT, ControlMode.PID, 62.5, 10.0, 0.1, 0.0, 0.7, false, false);

    private final CommandValidator validator =
            new CommandValidator(CommandRules.standard(DefaultParameters.commandLimits()));

    static Stream<Arguments> rules() {
        CommandContext legacy =
                new CommandContext(MachineState.TRANSPORT, ControlMode.LEGACY, 62.5, 10.0, 0.1, 0.0, 0.7, false, false);
        CommandContext faultUnacknowledged =
                new CommandContext(MachineState.FAULT, ControlMode.PID, 62.5, 10.0, 0.1, 0.0, 0.7, true, false);
        CommandContext faultCauseActive =
                new CommandContext(MachineState.FAULT, ControlMode.PID, 62.5, 10.0, 0.1, 0.0, 0.7, false, true);
        CommandContext faultCleared =
                new CommandContext(MachineState.FAULT, ControlMode.PID, 62.5, 10.0, 0.1, 0.0, 0.7, false, false);
        CommandContext off =
                new CommandContext(MachineState.OFF, ControlMode.PID, 62.5, 10.0, 0.1, 0.0, 0.7, false, false);
        return Stream.of(
                rule(Rule.SOURCE_NOT_ALLOWED, Issuer.AGENT, new Start(), off, Issuer.DASHBOARD, new Start(), off),
                rule(
                        Rule.STATE_NOT_ALLOWED,
                        Issuer.DASHBOARD,
                        new Start(),
                        RUNNING,
                        Issuer.DASHBOARD,
                        new Start(),
                        off),
                rule(
                        Rule.MODE_MISMATCH,
                        Issuer.AGENT,
                        new SetSetpoint(63),
                        legacy,
                        Issuer.AGENT,
                        new SetSetpoint(63),
                        RUNNING),
                rule(
                        Rule.SETPOINT_RANGE,
                        Issuer.AGENT,
                        new SetSetpoint(76),
                        RUNNING,
                        Issuer.AGENT,
                        new SetSetpoint(67),
                        RUNNING),
                rule(
                        Rule.SETPOINT_MAX_STEP,
                        Issuer.AGENT,
                        new SetSetpoint(68),
                        RUNNING,
                        Issuer.AGENT,
                        new SetSetpoint(67.5),
                        RUNNING),
                rule(
                        Rule.KP_RANGE,
                        Issuer.AGENT,
                        new SetPid(70, 0.1, 0),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(10, 0.1, 0),
                        RUNNING),
                rule(
                        Rule.KI_RANGE,
                        Issuer.AGENT,
                        new SetPid(10, 3, 0),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(10, 0.15, 0),
                        RUNNING),
                rule(
                        Rule.KD_RANGE,
                        Issuer.AGENT,
                        new SetPid(10, 0.1, 130),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(10, 0.1, 5),
                        RUNNING),
                // máx(20 % de 10, 5 % de 59,5) = 2,975
                rule(
                        Rule.KP_MAX_STEP_20PCT,
                        Issuer.AGENT,
                        new SetPid(13.5, 0.1, 0),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(12.9, 0.1, 0),
                        RUNNING),
                // máx(20 % de 0,1, 5 % de 2) = 0,1
                rule(
                        Rule.KI_MAX_STEP_20PCT,
                        Issuer.AGENT,
                        new SetPid(10, 0.25, 0),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(10, 0.19, 0),
                        RUNNING),
                // máx(20 % de 0, 5 % de 120) = 6
                rule(
                        Rule.KD_MAX_STEP_20PCT,
                        Issuer.AGENT,
                        new SetPid(10, 0.1, 7),
                        RUNNING,
                        Issuer.AGENT,
                        new SetPid(10, 0.1, 5),
                        RUNNING),
                rule(
                        Rule.BELT_PWM_RANGE,
                        Issuer.AGENT,
                        new SetBeltSpeed(0.3),
                        RUNNING,
                        Issuer.AGENT,
                        new SetBeltSpeed(0.5),
                        RUNNING),
                rule(
                        Rule.BELT_PWM_MAX_STEP,
                        Issuer.AGENT,
                        new SetBeltSpeed(1.0),
                        RUNNING,
                        Issuer.AGENT,
                        new SetBeltSpeed(0.9),
                        RUNNING),
                rule(
                        Rule.ALARMS_NOT_ACKNOWLEDGED,
                        Issuer.DASHBOARD,
                        new Reset(),
                        faultUnacknowledged,
                        Issuer.DASHBOARD,
                        new Reset(),
                        faultCleared),
                rule(
                        Rule.CAUSE_ACTIVE,
                        Issuer.DASHBOARD,
                        new Reset(),
                        faultCauseActive,
                        Issuer.DASHBOARD,
                        new Reset(),
                        faultCleared));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void eachRuleRejectsItsCaseAndAcceptsTheNeighbour(
            Rule rule,
            IssuedCommand invalid,
            CommandContext invalidContext,
            IssuedCommand valid,
            CommandContext validContext) {
        assertThat(validator.validate(invalid, invalidContext))
                .map(CommandRule.Rejection::rule)
                .hasValue(rule);
        assertThat(validator.validate(valid, validContext)).isEmpty();
    }

    @Test
    void theFirstViolatedRuleInTableOrderWins() {
        // El agente pide un setpoint fuera de rango en LEGACY: MODE_MISMATCH va antes que SETPOINT_RANGE.
        CommandContext legacy =
                new CommandContext(MachineState.TRANSPORT, ControlMode.LEGACY, 62.5, 10.0, 0.1, 0.0, 0.7, false, false);

        assertThat(validator.validate(issued(Issuer.AGENT, new SetSetpoint(90)), legacy))
                .map(CommandRule.Rejection::rule)
                .hasValue(Rule.MODE_MISMATCH);
    }

    @Test
    void rejectionDetailExplainsTheStep() {
        assertThat(validator.validate(issued(Issuer.AGENT, new SetPid(13.5, 0.1, 0)), RUNNING))
                .map(CommandRule.Rejection::detail)
                .hasValue("kp 10 → 13.5: el paso 3.5 supera el máximo permitido 2.975");
    }

    private static Arguments rule(
            Rule rule,
            Issuer invalidIssuer,
            MachineCommand invalid,
            CommandContext invalidContext,
            Issuer validIssuer,
            MachineCommand valid,
            CommandContext validContext) {
        return Arguments.of(
                rule, issued(invalidIssuer, invalid), invalidContext, issued(validIssuer, valid), validContext);
    }

    private static IssuedCommand issued(Issuer issuer, MachineCommand command) {
        return new IssuedCommand("7c9e6679-7425-40de-944b-e07fc1f90ae7", issuer, Optional.empty(), command);
    }
}
