package com.simex.twin.domain.control.thermal;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.shared.Percent;
import com.simex.twin.domain.shared.SimTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegacyOnOffControllerTest extends TemperatureControllerContract {

    @Override
    protected TemperatureController createController() {
        return new LegacyOnOffController(DefaultParameters.legacy());
    }

    @ParameterizedTest(name = "S={0} SP={1} SI={2} T_meas={3} → {4} %")
    @CsvSource({
        "true,  false, false, 45.0, 100", // calentando sin piezas
        "true,  false, false, 50.0, 0", // umbral alcanzado (T ≥ 50)
        "true,  false, true,  45.0, 0", // metal en el inductivo: la tabla apaga la resistencia
        "true,  true,  true,  45.0, 100",
        "false, false, false, 20.0, 0" // máquina no operativa
    })
    void usesOnlyTheHeaterOutputOfTheTruthTable(
            boolean operative, boolean presence, boolean inductive, double measuredC, double expected) {
        TemperatureController legacy = createController();

        assertThat(legacy.compute(new ControllerInput(SimTime.ZERO, measuredC, operative, presence, inductive))
                        .value())
                .isEqualTo(expected);
    }

    @Test
    void holdsItsOutputUntilTheNextSample() {
        TemperatureController legacy = createController();
        assertThat(legacy.compute(input(0, 40.0)).value()).isEqualTo(Percent.MAX);

        for (long ms = 100; ms <= 1000; ms += 100) {
            assertThat(legacy.compute(input(ms, 60.0)).value()).isEqualTo(Percent.MAX); // ya pasó el umbral
        }
        assertThat(legacy.compute(input(1100, 60.0)).value()).isZero(); // muestreo de 1010 ms en el tick de 1100
    }

    private static ControllerInput input(long nowMs, double measuredC) {
        return new ControllerInput(new SimTime(nowMs), measuredC, true, false, false);
    }
}
