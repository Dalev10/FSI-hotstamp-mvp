package com.simex.twin.domain.control.thermal;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SampleClockTest {

    @Test
    void aPeriodThatIsNotATickMultipleSamplesOnIdealTimes() {
        List<Long> sampleTimesMs = sampleTimes(new SampleClock(new SimDuration(1010)), 3_600_000);

        // n·1010 ms cae en el primer tick que lo alcanza; la variación es de un tick como máximo.
        assertThat(sampleTimesMs).startsWith(0L, 1100L, 2100L, 3100L);
        for (int i = 1; i < sampleTimesMs.size(); i++) {
            assertThat(sampleTimesMs.get(i) - sampleTimesMs.get(i - 1)).isBetween(1000L, 1100L);
        }
        double meanPeriodMs = (double) sampleTimesMs.getLast() / (sampleTimesMs.size() - 1);
        assertThat(meanPeriodMs).isCloseTo(1010.0, within(0.1));
    }

    @Test
    void aTickMultiplePeriodIsExact() {
        List<Long> sampleTimesMs = sampleTimes(new SampleClock(new SimDuration(1000)), 5_000);

        assertThat(sampleTimesMs).containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L);
    }

    @Test
    void sampleNowForcesTheNextScanToSample() {
        SampleClock clock = new SampleClock(new SimDuration(1000));
        clock.isDue(SimTime.ZERO);
        clock.sampleNow();

        assertThat(clock.isDue(new SimTime(100))).isTrue();
        assertThat(clock.isDue(new SimTime(200))).isFalse();
    }

    private static List<Long> sampleTimes(SampleClock clock, long untilMs) {
        List<Long> times = new ArrayList<>();
        for (long ms = 0; ms <= untilMs; ms += TICK.ms()) {
            if (clock.isDue(new SimTime(ms))) {
                times.add(ms);
            }
        }
        return times;
    }
}
