package com.simex.twin.domain.control.command;

import java.util.Optional;

/** Una regla de seguridad de comandos (patrón Specification): su código y un detalle en español si se viola. */
@FunctionalInterface
public interface CommandRule {

    Optional<Rejection> check(IssuedCommand command, CommandContext context);

    record Rejection(Rule rule, String detail) {}
}
