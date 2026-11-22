package com.simex.twin.domain.control.alarm;

import com.simex.twin.domain.shared.SimTime;
import java.util.Optional;

public record ActiveAlarm(AlarmCode code, SimTime raisedAt, boolean acknowledged, Optional<String> detail) {

    public boolean latched() {
        return code.latched();
    }

    ActiveAlarm acknowledge() {
        return new ActiveAlarm(code, raisedAt, true, detail);
    }
}
