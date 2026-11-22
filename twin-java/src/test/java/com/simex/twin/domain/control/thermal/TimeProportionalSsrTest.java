package com.simex.twin.domain.control.thermal;

import static com.simex.twin.domain.DefaultParameters.SSR_WINDOW;
import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimDuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimeProportionalSsrTest {

    private static final int WINDOW_TICKS = 20;

    /** Sección 9.2: la duty media en 100 ventanas es la pedida ±0,1 %. */
    @ParameterizedTest(name = "duty {0}")
    @ValueSource(doubles = {0.0, 0.013, 0.2288, 0.5, 0.977, 1.0})
    void meanDutyOverAHundredWindowsMatchesTheRequest(double duty) {
        TimeProportionalSsr ssr = new TimeProportionalSsr(SSR_WINDOW, TICK);
        int onTicks = 0;
        for (int i = 0; i < 100 * WINDOW_TICKS; i++) {
            onTicks += ssr.update(new DutyCycle(duty)) ? 1 : 0;
        }

        assertThat((double) onTicks / (100 * WINDOW_TICKS)).isCloseTo(duty, within(0.001));
    }

    @Test
    void theSsrIsOnAtTheStartOfTheWindowAndLatchesTheDuty() {
        TimeProportionalSsr ssr = new TimeProportionalSsr(SSR_WINDOW, TICK);
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < WINDOW_TICKS; i++) {
            // A mitad de la ventana se pide otra duty: no cambia nada hasta la próxima ventana.
            pattern.append(ssr.update(new DutyCycle(i < 10 ? 0.25 : 0.9)) ? '1' : '0');
        }

        assertThat(pattern).hasToString("11111000000000000000");
        assertThat(ssr.latchedDuty()).isEqualTo(new DutyCycle(0.25));
    }

    @Test
    void windowMustBeAWholeNumberOfTicks() {
        assertThatThrownBy(() -> new TimeProportionalSsr(new SimDuration(2050), TICK))
                .isInstanceOf(DomainException.class);
    }
}
