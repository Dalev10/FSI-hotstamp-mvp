package com.simex.twin.domain.plant.thermal;

import com.simex.twin.domain.shared.DomainException;

/** Retardo puro de un número entero de ticks (búfer circular). Arranca lleno de ceros. */
public final class DelayLine {

    private final double[] buffer;
    private int head;

    public DelayLine(int delayTicks) {
        if (delayTicks < 0) {
            throw new DomainException("Retardo negativo: " + delayTicks + " ticks");
        }
        this.buffer = new double[delayTicks];
    }

    /** Entra el valor de este tick y sale el que entró hace {@code delayTicks} ticks. */
    public double shift(double input) {
        if (buffer.length == 0) {
            return input;
        }
        double output = buffer[head];
        buffer[head] = input;
        head = (head + 1) % buffer.length;
        return output;
    }
}
