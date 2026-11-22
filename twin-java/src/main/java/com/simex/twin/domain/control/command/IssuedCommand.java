package com.simex.twin.domain.control.command;

import java.util.Optional;

/**
 * Un comando con su origen.
 *
 * @param commandId clave de idempotencia
 * @param issuedBy quién lo envía
 * @param reason motivo opcional, para auditoría
 */
public record IssuedCommand(String commandId, Issuer issuedBy, Optional<String> reason, MachineCommand command) {

    public enum Issuer {
        AGENT,
        DASHBOARD,
        SCENARIO
    }
}
