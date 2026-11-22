package com.simex.twin.application.engine;

import com.simex.twin.domain.control.command.CommandResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Últimos resultados por {@code commandId} (LRU). Lo usa solo el hilo del motor, así que no necesita locks. */
public final class IdempotencyStore {

    private final int capacity;
    // accessOrder = true: el primero es el usado hace más tiempo.
    private final Map<String, CommandResult> results = new LinkedHashMap<>(16, 0.75f, true);

    public IdempotencyStore(int capacity) {
        this.capacity = capacity;
    }

    public Optional<CommandResult> find(String commandId) {
        return Optional.ofNullable(results.get(commandId));
    }

    public void remember(CommandResult result) {
        results.put(result.commandId(), result);
        if (results.size() > capacity) {
            results.remove(results.keySet().iterator().next());
        }
    }
}
