package com.simex.twin.domain.control.command;

import com.simex.twin.domain.control.command.CommandRule.Rejection;
import java.util.List;
import java.util.Optional;

/** Aplica las reglas en orden; la primera violación decide el rechazo. */
public final class CommandValidator {

    private final List<CommandRule> rules;

    public CommandValidator(List<CommandRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Optional<Rejection> validate(IssuedCommand command, CommandContext context) {
        return rules.stream()
                .map(rule -> rule.check(command, context))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
