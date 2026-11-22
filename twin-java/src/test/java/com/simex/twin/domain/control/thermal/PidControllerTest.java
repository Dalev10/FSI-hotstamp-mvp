package com.simex.twin.domain.control.thermal;

import static com.simex.twin.domain.DefaultParameters.DEFAULT_SETPOINT_C;
import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.Percent;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PidControllerTest extends TemperatureControllerContract {

    private long tick;

    @Override
    protected TemperatureController createController() {
        return pid();
    }

    @Test
    void outputIsHeldBetweenSamples() {
        PidController pid = pid();
        Percent first = step(pid, 60.0);
        for (int i = 1; i < 10; i++) {
            assertThat(step(pid, 60.0 - i)).isSameAs(first); // cambia la medida, pero no toca muestrear
        }

        assertThat(step(pid, 50.0)).isNotEqualTo(first);
    }

    @Test
    void integralDoesNotWindUpWhileSaturatedHigh() {
        PidController pid = pid();
        for (int i = 0; i < 6_000; i++) { // 10 min a 25 °C: salida saturada en 100 %
            assertThat(step(pid, 25.0).value()).isEqualTo(Percent.MAX);
        }

        // Sin windup, en el setpoint la salida cae a lo que dice la integral acumulada antes de saturar: nada.
        assertThat(sample(pid, DEFAULT_SETPOINT_C).value()).isZero();
    }

    @Test
    void gainChangeIsBumpless() {
        PidController pid = pid();
        for (int i = 0; i < 600; i++) {
            step(pid, 62.0);
        }
        Percent before = sample(pid, 62.0);

        pid.setGains(12.0, 0.12, 0.0);

        assertThat(Math.abs(sample(pid, 62.0).value() - before.value())).isLessThan(0.5);
    }

    @ParameterizedTest(name = "salida LEGACY {0} % con T = {1} °C")
    @CsvSource({"100, 40.0", "0, 51.0", "100, 49.0", "0, 70.0"})
    void switchFromLegacyIsBumpless(double legacyOutput, double measuredC) {
        PidController pid = pid();
        pid.reset(new ControllerState(new Percent(legacyOutput)));

        assertThat(sample(pid, measuredC).value()).isCloseTo(legacyOutput, within(1e-9));
    }

    @Test
    void derivativeActsOnTheMeasurementSoASetpointChangeGivesNoKick() {
        PidController pid =
                new PidController(new PidParameters(10.0, 0.0, 30.0, new SimDuration(1000), 10), DEFAULT_SETPOINT_C);
        sample(pid, 60.0);
        Percent before = sample(pid, 60.0);

        pid.setSetpointC(DEFAULT_SETPOINT_C + 2);

        assertThat(sample(pid, 60.0).value() - before.value()).isCloseTo(10.0 * 2, within(1e-9));
    }

    @Test
    void filteredDerivativeOpposesARisingMeasurement() {
        PidController withD =
                new PidController(new PidParameters(10.0, 0.0, 30.0, new SimDuration(1000), 10), DEFAULT_SETPOINT_C);
        PidController withoutD =
                new PidController(new PidParameters(10.0, 0.0, 0.0, new SimDuration(1000), 10), DEFAULT_SETPOINT_C);
        for (PidController pid : new PidController[] {withD, withoutD}) {
            pid.compute(new ControllerInput(SimTime.ZERO, 55.0, true, false, false));
        }
        ControllerInput rising = new ControllerInput(new SimTime(1000), 56.0, true, false, false);

        // Sin D: 10·6,5 = 65 %. Con D (τ_f = 0,3 s): la medida sube 1 °C/s y la derivada filtrada resta.
        assertThat(withoutD.compute(rising).value()).isCloseTo(65.0, within(1e-9));
        assertThat(withD.compute(rising).value()).isCloseTo(65.0 - 30.0 / 1.3, within(1e-9));
    }

    @Test
    void restartIsAColdStartWithAnEmptyIntegral() {
        PidController pid = pid();
        for (int i = 0; i < 600; i++) {
            step(pid, 62.0); // acumula integral
        }

        pid.restart();

        // Sin integral previa: u = kp·e + ki·e·Ts = 10·2,5 + 0,1·2,5·1 en el primer muestreo.
        assertThat(sample(pid, 60.0).value()).isCloseTo(25.25, within(1e-9));
    }

    @Test
    void kpMustBePositive() {
        assertThatThrownBy(() -> pid().setGains(0, 0.1, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() ->
                        new PidController(new PidParameters(-1, 0.1, 0, new SimDuration(1000), 10), DEFAULT_SETPOINT_C))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void gainsAndSetpointAreReadable() {
        PidController pid = pid();
        pid.setGains(9.5, 0.11, 1.0);
        pid.setSetpointC(64.0);

        assertThat(pid.kp()).isEqualTo(9.5);
        assertThat(pid.ki()).isEqualTo(0.11);
        assertThat(pid.kd()).isEqualTo(1.0);
        assertThat(pid.setpointC()).isEqualTo(64.0);
    }

    private static PidController pid() {
        return new PidController(DefaultParameters.pid(), DEFAULT_SETPOINT_C);
    }

    /** Un tick. */
    private Percent step(TemperatureController controller, double measuredC) {
        return controller.compute(new ControllerInput(new SimTime(tick++ * TICK.ms()), measuredC, true, false, false));
    }

    /** Avanza hasta el próximo muestreo (1 s) y devuelve su salida. */
    private Percent sample(TemperatureController controller, double measuredC) {
        while (tick % 10 != 0) {
            step(controller, measuredC);
        }
        return step(controller, measuredC);
    }
}
