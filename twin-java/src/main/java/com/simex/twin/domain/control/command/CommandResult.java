package com.simex.twin.domain.control.command;

import com.simex.twin.domain.shared.SimTime;

public sealed interface CommandResult {

    String commandId();

    record Applied(String commandId, SimTime appliedAt) implements CommandResult {}

    record Rejected(String commandId, Rule rule, String detail) implements CommandResult {}
}
