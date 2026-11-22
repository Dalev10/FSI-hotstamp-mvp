package com.simex.twin.domain.io;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simex.twin.domain.shared.DutyCycle;
import org.junit.jupiter.api.Test;

/**
 * Contrato de {@link PlantPort} (sustitución de Liskov): toda implementación responde a las salidas escritas de forma
 * coherente. Lo heredan las pruebas de {@code SimulatedPlant} y de {@link FakePlant}.
 */
public abstract class PlantPortContract {

    private static final int SETTLE_TICKS = 30;

    protected abstract PlantPort createPlant();

    @Test
    public void inputsAreAvailableBeforeAnyOutputIsWritten() {
        assertThatCode(createPlant()::readInputs).doesNotThrowAnyException();
    }

    @Test
    public void safeOutputsKeepCylindersRetractedAndTheBeltStopped() {
        PlantPort plant = createPlant();
        ProcessInputs inputs = run(plant, ProcessOutputs.SAFE, SETTLE_TICKS);

        assertThat(inputs.discardPosition()).isEqualTo(CylinderPosition.RETRACTED);
        assertThat(inputs.stampPosition()).isEqualTo(CylinderPosition.RETRACTED);
        assertThat(inputs.beltSpeedMps()).isZero();
    }

    @Test
    public void energizedValvesExtendTheCylindersAndReleasingRetractsThem() {
        PlantPort plant = createPlant();
        ProcessOutputs energized =
                new ProcessOutputs(false, DutyCycle.ZERO, ValveCommand.ENERGIZED, ValveCommand.ENERGIZED);

        ProcessInputs extended = run(plant, energized, SETTLE_TICKS);
        assertThat(extended.discardPosition()).isEqualTo(CylinderPosition.EXTENDED);
        assertThat(extended.stampPosition()).isEqualTo(CylinderPosition.EXTENDED);

        ProcessInputs retracted = run(plant, ProcessOutputs.SAFE, SETTLE_TICKS);
        assertThat(retracted.discardPosition()).isEqualTo(CylinderPosition.RETRACTED);
        assertThat(retracted.stampPosition()).isEqualTo(CylinderPosition.RETRACTED);
    }

    @Test
    public void motorPwmMovesTheBeltAndZeroStopsIt() {
        PlantPort plant = createPlant();
        ProcessOutputs running =
                new ProcessOutputs(false, new DutyCycle(0.7), ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED);

        assertThat(run(plant, running, SETTLE_TICKS).beltSpeedMps()).isPositive();
        assertThat(run(plant, ProcessOutputs.SAFE, SETTLE_TICKS).beltSpeedMps()).isZero();
    }

    @Test
    public void theHeaterRaisesTheThermocoupleCounts() {
        PlantPort plant = createPlant();
        int before = plant.readInputs().tcCounts();
        ProcessOutputs heating =
                new ProcessOutputs(true, DutyCycle.ZERO, ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED);

        assertThat(run(plant, heating, 600).tcCounts()).isGreaterThan(before + 10);
    }

    private static ProcessInputs run(PlantPort plant, ProcessOutputs outputs, int ticks) {
        for (int i = 0; i < ticks; i++) {
            plant.writeOutputs(outputs);
            plant.advance(TICK);
        }
        return plant.readInputs();
    }
}
