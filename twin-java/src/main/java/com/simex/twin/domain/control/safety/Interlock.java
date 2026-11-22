package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.sequence.MachineState;
import java.util.Optional;

/** Un interlock de la cadena de seguridad (Chain of Responsibility). */
public interface Interlock {

    /** La alarma que levanta al disparar; identifica también la causa a revisar en el {@code RESET}. */
    AlarmCode alarm();

    Optional<Trip> evaluate(SafetyInput input);

    /** Si la condición que disparó sigue presente ({@code CAUSE_ACTIVE}). */
    default boolean causeActive(SafetyInput input) {
        return evaluate(input).isPresent();
    }

    /**
     * @param target PARO o FALLA
     * @param detail por ejemplo, el cilindro de un timeout neumático
     */
    record Trip(MachineState target, AlarmCode alarm, Optional<String> detail) {}
}
