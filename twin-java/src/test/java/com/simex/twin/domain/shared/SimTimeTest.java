package com.simex.twin.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SimTimeTest {

    @Test
    void negativeTimesAndDurationsAreRejected() {
        assertThatThrownBy(() -> new SimTime(-1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SimDuration(-1)).isInstanceOf(DomainException.class);
    }

    @Test
    void plusAdvancesTheInstant() {
        assertThat(new SimTime(1_000).plus(new SimDuration(100))).isEqualTo(new SimTime(1_100));
    }

    @Test
    void minusGivesTheElapsedDuration() {
        assertThat(new SimTime(1_100).minus(new SimTime(1_000))).isEqualTo(new SimDuration(100));
    }

    @Test
    void minusRejectsALaterInstant() {
        assertThatThrownBy(() -> new SimTime(1_000).minus(new SimTime(1_100))).isInstanceOf(DomainException.class);
    }

    @Test
    void overflowIsRejected() {
        assertThatThrownBy(() -> new SimTime(Long.MAX_VALUE).plus(new SimDuration(1)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void durationConvertsToSeconds() {
        assertThat(new SimDuration(100).seconds()).isEqualTo(0.1);
    }
}
