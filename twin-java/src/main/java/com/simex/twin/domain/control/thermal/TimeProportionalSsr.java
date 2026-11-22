package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimDuration;

/**
 * SSR proporcional en el tiempo. Al inicio de cada ventana fija el ciclo útil pedido y enciende los primeros
 * {@code onTicks = round(duty · N + carry)} ticks, con {@code carry = duty · N + carry − onTicks} (difusión del error),
 * así que el ciclo útil medio es exacto.
 */
public final class TimeProportionalSsr {

    private final int windowTicks;
    private int tickInWindow;
    private int onTicks;
    private double carry;
    private DutyCycle latchedDuty = DutyCycle.ZERO;

    public TimeProportionalSsr(SimDuration window, SimDuration tick) {
        if (window.ms() % tick.ms() != 0) {
            throw new DomainException("La ventana del SSR debe ser múltiplo del tick");
        }
        this.windowTicks = Math.toIntExact(window.ms() / tick.ms());
    }

    /** Estado del SSR en este tick. Se llama una vez por tick. */
    public boolean update(DutyCycle requested) {
        if (tickInWindow == 0) {
            double exactTicks = requested.value() * windowTicks + carry;
            onTicks = Math.clamp(Math.round(exactTicks), 0, windowTicks);
            carry = exactTicks - onTicks;
            latchedDuty = requested;
        }
        boolean on = tickInWindow < onTicks;
        tickInWindow = (tickInWindow + 1) % windowTicks;
        return on;
    }

    /** Ciclo útil fijado al inicio de la ventana actual (el {@code heaterDuty} de la telemetría). */
    public DutyCycle latchedDuty() {
        return latchedDuty;
    }
}
