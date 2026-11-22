package com.simex.twin.domain.plant.pneumatics;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ValveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Carreras del anexo A: 350 ms → EXTENDED en el tick 4; fuga ×1,5 → tick 6; fuga ×5 → sigue extendiendo a 1500 ms. */
class PneumaticCylinderTest {

    @ParameterizedTest(name = "factor {0} → EXTENDED en el tick {1}")
    @CsvSource({"1.0, 4", "1.5, 6", "5.0, 18"})
    void extensionEndsOnTheExpectedTick(double strokeFactor, int expectedTick) {
        PneumaticCylinder cylinder = new PneumaticCylinder(350);
        cylinder.command(ValveCommand.ENERGIZED);

        int tick = 0;
        boolean reached = false;
        while (!reached) {
            tick++;
            reached = cylinder.advance(TICK, strokeFactor);
        }

        assertThat(tick).isEqualTo(expectedTick);
        assertThat(cylinder.position()).isEqualTo(CylinderPosition.EXTENDED);
    }

    @Test
    void withAFivefoldLeakTheCylinderIsStillExtendingAtTheTimeout() {
        PneumaticCylinder cylinder = new PneumaticCylinder(350);
        cylinder.command(ValveCommand.ENERGIZED);
        for (int i = 0; i < 15; i++) { // 1500 ms
            cylinder.advance(TICK, 5.0);
        }

        assertThat(cylinder.position()).isEqualTo(CylinderPosition.EXTENDING);
    }

    @Test
    void releasingTheValveRetractsWithTheSameStroke() {
        PneumaticCylinder cylinder = extended();
        cylinder.command(ValveCommand.DE_ENERGIZED);
        for (int i = 0; i < 3; i++) {
            cylinder.advance(TICK, 1.0);
        }
        assertThat(cylinder.position()).isEqualTo(CylinderPosition.RETRACTING);

        cylinder.advance(TICK, 1.0);
        assertThat(cylinder.position()).isEqualTo(CylinderPosition.RETRACTED);
    }

    @Test
    void reversingMidStrokeContinuesFromTheCurrentTravel() {
        PneumaticCylinder cylinder = new PneumaticCylinder(350);
        cylinder.command(ValveCommand.ENERGIZED);
        cylinder.advance(TICK, 1.0);
        cylinder.advance(TICK, 1.0); // 200 ms de recorrido
        cylinder.command(ValveCommand.DE_ENERGIZED);
        cylinder.advance(TICK, 1.0);
        assertThat(cylinder.position()).isEqualTo(CylinderPosition.RETRACTING);

        cylinder.advance(TICK, 1.0);
        assertThat(cylinder.position()).isEqualTo(CylinderPosition.RETRACTED);
    }

    @Test
    void reachingTheEndIsReportedOnlyOnce() {
        PneumaticCylinder cylinder = extended();

        assertThat(cylinder.advance(TICK, 1.0)).isFalse();
    }

    private static PneumaticCylinder extended() {
        PneumaticCylinder cylinder = new PneumaticCylinder(350);
        cylinder.command(ValveCommand.ENERGIZED);
        for (int i = 0; i < 4; i++) {
            cylinder.advance(TICK, 1.0);
        }
        return cylinder;
    }
}
