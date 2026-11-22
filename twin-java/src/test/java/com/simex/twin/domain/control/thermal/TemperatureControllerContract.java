package com.simex.twin.domain.control.thermal;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.shared.Percent;
import com.simex.twin.domain.shared.RandomStream;
import com.simex.twin.domain.shared.RandomStreams;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contrato de {@link TemperatureController} (sustitución de Liskov): salida en [0, 100] %, determinismo y
 * {@code reset} limpio. Lo heredan las pruebas de LEGACY y de PID.
 */
public abstract class TemperatureControllerContract {

    private static final int TICKS = 3_000;

    protected abstract TemperatureController createController();

    @Test
    public void outputStaysWithinZeroToHundredPercent() {
        assertThat(outputs(createController(), new RandomStreams(1), 0))
                .allSatisfy(u -> assertThat(u.value()).isBetween(0.0, Percent.MAX));
    }

    @Test
    public void sameInputsGiveSameOutputs() {
        assertThat(outputs(createController(), new RandomStreams(7), 0))
                .containsExactlyElementsOf(outputs(createController(), new RandomStreams(7), 0));
    }

    @Test
    public void resetForgetsThePastHistory() {
        TemperatureController first = createController();
        TemperatureController second = createController();
        outputs(first, new RandomStreams(1), 0);
        outputs(second, new RandomStreams(2), 0);
        ControllerState state = new ControllerState(new Percent(40));
        first.reset(state);
        second.reset(state);

        assertThat(first.snapshot()).isEqualTo(state);
        assertThat(outputs(first, new RandomStreams(3), TICKS))
                .containsExactlyElementsOf(outputs(second, new RandomStreams(3), TICKS));
    }

    /** Temperaturas medidas al azar entre 0 y 120 °C, con sensores al azar. */
    private static List<Percent> outputs(TemperatureController controller, RandomStreams streams, long fromTick) {
        RandomStream random = streams.get(RandomStreams.Name.TC_NOISE);
        List<Percent> outputs = new ArrayList<>();
        for (long tick = fromTick; tick < fromTick + TICKS; tick++) {
            ControllerInput in = new ControllerInput(
                    new SimTime(tick * TICK.ms()),
                    random.nextDouble() * 120,
                    random.nextDouble() < 0.9,
                    random.nextDouble() < 0.2,
                    random.nextDouble() < 0.1);
            outputs.add(controller.compute(in));
        }
        return outputs;
    }
}
