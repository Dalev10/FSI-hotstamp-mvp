package com.simex.twin.domain.control.safety;

import com.simex.twin.domain.control.alarm.AlarmCode;
import java.util.List;
import java.util.Optional;

/** Evalúa los interlocks por prioridad y se detiene en el primer disparo. */
public final class InterlockChain {

    private final List<Interlock> interlocks;

    public InterlockChain(List<Interlock> interlocks) {
        this.interlocks = List.copyOf(interlocks);
    }

    public static InterlockChain standard(SafetyParameters parameters) {
        return new InterlockChain(List.of(
                new Interlocks.EmergencyStopInterlock(),
                new Interlocks.ThermocoupleOpenInterlock(parameters.openTcCountsThreshold()),
                new Interlocks.OverTemperatureInterlock(parameters.overTempTripC()),
                new Interlocks.PneumaticTimeoutInterlock(
                        parameters.strokeTimeout().ms())));
    }

    public Optional<Interlock.Trip> evaluate(SafetyInput input) {
        return interlocks.stream()
                .map(interlock -> interlock.evaluate(input))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Si persiste la causa del interlock que levantó {@code alarm}. */
    public boolean causeActive(AlarmCode alarm, SafetyInput input) {
        return interlocks.stream()
                .filter(interlock -> interlock.alarm() == alarm)
                .anyMatch(interlock -> interlock.causeActive(input));
    }
}
