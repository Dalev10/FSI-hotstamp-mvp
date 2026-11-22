package com.simex.twin.domain.control.alarm;

import com.simex.twin.domain.control.ControlEvent;
import com.simex.twin.domain.shared.SimTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Alarmas activas, en el orden en que se levantaron. Cada cambio deja un hecho para la máquina. */
public final class AlarmRegistry {

    private final Map<AlarmCode, ActiveAlarm> active = new LinkedHashMap<>();

    /** Levanta la alarma si no está activa; una alarma activa no se vuelve a levantar. */
    public void raise(AlarmCode code, SimTime now, Optional<String> detail, List<ControlEvent> events) {
        if (!active.containsKey(code)) {
            active.put(code, new ActiveAlarm(code, now, false, detail));
            events.add(new ControlEvent.AlarmRaised(code, detail));
        }
    }

    /** Despeja una advertencia. Las críticas solo se despejan con {@link #clearLatched}. */
    public void clear(AlarmCode code, List<ControlEvent> events) {
        if (!code.latched() && active.remove(code) != null) {
            events.add(new ControlEvent.AlarmCleared(code));
        }
    }

    /** Reconoce una alarma, o todas si {@code code} está vacío. */
    public void acknowledge(Optional<AlarmCode> code) {
        active.replaceAll((c, alarm) -> code.isEmpty() || code.get() == c ? alarm.acknowledge() : alarm);
    }

    public boolean hasUnacknowledgedCritical() {
        return active.values().stream().anyMatch(a -> a.latched() && !a.acknowledged());
    }

    /** En el {@code RESET}: retira las alarmas enclavadas. */
    public void clearLatched(List<ControlEvent> events) {
        for (Iterator<ActiveAlarm> it = active.values().iterator(); it.hasNext(); ) {
            ActiveAlarm alarm = it.next();
            if (alarm.latched()) {
                it.remove();
                events.add(new ControlEvent.AlarmCleared(alarm.code()));
            }
        }
    }

    public List<ActiveAlarm> active() {
        return List.copyOf(active.values());
    }
}
