package com.simex.twin.domain.plant.thermal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.SimDuration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelayLineAndLagTest {

    @Test
    void delayLineReturnsZerosThenTheInputsInOrder() {
        DelayLine delay = new DelayLine(3);
        List<Double> outputs = new ArrayList<>();
        for (double input : new double[] {1, 2, 3, 4, 5}) {
            outputs.add(delay.shift(input));
        }

        assertThat(outputs).containsExactly(0.0, 0.0, 0.0, 1.0, 2.0);
    }

    @Test
    void zeroDelayPassesTheInputThrough() {
        assertThat(new DelayLine(0).shift(7.0)).isEqualTo(7.0);
    }

    @Test
    void negativeDelayIsRejected() {
        assertThatThrownBy(() -> new DelayLine(-1)).isInstanceOf(DomainException.class);
    }

    @Test
    void lagReaches63PercentOfAStepAfterOneTimeConstant() {
        SimDuration dt = new SimDuration(100);
        FirstOrderLag lag = new FirstOrderLag(new SimDuration(4000), 0.0);
        for (int i = 0; i < 40; i++) {
            lag.update(1.0, dt);
        }

        assertThat(lag.value()).isCloseTo(1 - 1 / Math.E, within(0.01));
    }
}
