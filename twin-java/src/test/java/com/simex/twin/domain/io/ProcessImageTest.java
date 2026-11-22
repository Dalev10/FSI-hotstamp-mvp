package com.simex.twin.domain.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.DutyCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProcessImageTest {

    @Test
    void validInputsAreAccepted() {
        assertThatCode(() -> inputs(477, 25.0, 0.175)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"-1, 25.0, 0.0", "0, NaN, 0.0", "0, 25.0, -0.1", "0, 25.0, Infinity"})
    void invalidInputsAreRejected(int tcCounts, double ambientC, double beltSpeedMps) {
        assertThatThrownBy(() -> inputs(tcCounts, ambientC, beltSpeedMps)).isInstanceOf(DomainException.class);
    }

    @Test
    void safeOutputsTurnEverythingOff() {
        assertThat(ProcessOutputs.SAFE)
                .isEqualTo(new ProcessOutputs(
                        false, new DutyCycle(0), ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED));
    }

    private static ProcessInputs inputs(int tcCounts, double ambientC, double beltSpeedMps) {
        return new ProcessInputs(
                tcCounts, ambientC, false, false, CylinderPosition.RETRACTED, CylinderPosition.RETRACTED, beltSpeedMps);
    }
}
