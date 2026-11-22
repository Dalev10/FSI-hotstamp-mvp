package com.simex.twin.domain.fault;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fallas activas de la corrida, en orden de inyección. Compone sus aportes en cada tick y retira las que vencen. Los
 * {@code faultId} se derivan de la semilla y del número de falla, así que son reproducibles (el golden los incluye).
 */
public final class FaultInjector {

    /** Duración por defecto de la ráfaga de metales, si no se indica (tabla 4.10). */
    private static final SimDuration METAL_BURST_DEFAULT = new SimDuration(120_000);

    private final long seed;
    private final Map<String, ActiveFault> active = new LinkedHashMap<>();
    private PlantModifiers current = PlantModifiers.neutral();
    private SimTime now = SimTime.ZERO;
    private int injected;

    /**
     * @param duration vacío: dura hasta que se retira
     */
    public record ActiveFault(String faultId, Fault fault, SimTime startedAt, Optional<SimDuration> duration) {

        boolean expiredAt(SimTime time) {
            return duration.isPresent()
                    && time.ms() >= startedAt.ms() + duration.get().ms();
        }
    }

    public FaultInjector(long seed) {
        this.seed = seed;
    }

    public ActiveFault inject(Fault fault, Optional<SimDuration> duration, SimTime at) {
        Optional<SimDuration> effective = duration.isEmpty() && fault.type() == FaultType.METAL_BURST
                ? Optional.of(METAL_BURST_DEFAULT)
                : duration;
        String id = UUID.nameUUIDFromBytes(("fault:" + seed + ":" + ++injected).getBytes(StandardCharsets.UTF_8))
                .toString();
        ActiveFault activeFault = new ActiveFault(id, fault, at, effective);
        active.put(id, activeFault);
        now = at; // se inyecta entre ticks: el próximo tick empieza en at
        recompute();
        return activeFault;
    }

    public Optional<ActiveFault> remove(String faultId) {
        Optional<ActiveFault> removed = Optional.ofNullable(active.remove(faultId));
        recompute();
        return removed;
    }

    /** Avanza a {@code time}: retira las vencidas (y las devuelve) y recalcula los modificadores. */
    public List<ActiveFault> update(SimTime time) {
        now = time;
        List<ActiveFault> expired = new ArrayList<>();
        for (Iterator<ActiveFault> it = active.values().iterator(); it.hasNext(); ) {
            ActiveFault fault = it.next();
            if (fault.expiredAt(time)) {
                it.remove();
                expired.add(fault);
            }
        }
        recompute();
        return expired;
    }

    /** Modificadores vigentes; los consulta la planta en cada lectura y avance. */
    public PlantModifiers current() {
        return current;
    }

    public List<ActiveFault> active() {
        return List.copyOf(active.values());
    }

    private void recompute() {
        PlantModifiers m = PlantModifiers.neutral();
        for (ActiveFault fault : active.values()) {
            m = fault.fault().contribute(m, now.minus(fault.startedAt()));
        }
        current = m;
    }
}
