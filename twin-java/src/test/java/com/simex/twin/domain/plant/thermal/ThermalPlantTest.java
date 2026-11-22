package com.simex.twin.domain.plant.thermal;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.SimDuration;
import org.junit.jupiter.api.Test;

/** Filas térmicas del anexo A del plan, simulando con los parámetros por defecto y T_amb = 25 °C. */
class ThermalPlantTest {

    private static final double AMBIENT_C = 25.0;
    private static final double TAU_S = 920.0;
    private static final double DELTA_T_FULL_POWER_C = 163.875; // η · P · R_th

    private final ThermalPlant plant = new ThermalPlant(DefaultParameters.thermal(), TICK);

    @Test
    void timeConstantIsRthTimesCapacitance() {
        ThermalParameters p = DefaultParameters.thermal();
        assertThat(p.rthKPerW() * p.capacitanceJPerK()).isCloseTo(TAU_S, within(1e-9));

        // Dinámicamente: sin potencia, el exceso sobre el ambiente cae a 1/e en τ.
        ThermalPlant hot = new ThermalPlant(withInitialTemp(p, AMBIENT_C + 100), TICK);
        run(hot, 0.0, TAU_S);
        assertThat(hot.platenTempC() - AMBIENT_C).isCloseTo(100 / Math.E, within(0.05));
    }

    @Test
    void fullPowerEquilibriumIsAmbientPlusEtaPR() {
        run(plant, 1.0, 10 * TAU_S);

        assertThat(plant.platenTempC()).isCloseTo(AMBIENT_C + DELTA_T_FULL_POWER_C, within(0.5));
    }

    @Test
    void fullPowerStepReaches128At925Seconds() {
        run(plant, 1.0, 925);

        assertThat(plant.platenTempC()).isCloseTo(128.59, within(0.5));
    }

    @Test
    void fullPowerReaches50And62point5OnTime() {
        assertThat(secondsToReach(50.0)).isCloseTo(157.3, within(1.0));
        assertThat(secondsToReach(62.5)).isCloseTo(244.1, within(1.0));
    }

    @Test
    void dutyOf22point88PercentHolds62point5() {
        run(plant, 0.2288, 10 * TAU_S);

        // ±0,1 % de ciclo útil equivale a ±0,164 °C en el equilibrio.
        assertThat(plant.platenTempC()).isCloseTo(62.5, within(0.001 * DELTA_T_FULL_POWER_C));
    }

    @Test
    void stampedPieceDropsThePlatenAboutPoint31Degrees() {
        SimDuration dwell = new SimDuration(1000);
        double pieceHeatW = DefaultParameters.thermal().pieceHeatJ() / dwell.seconds();
        for (long t = 0; t < dwell.ms(); t += TICK.ms()) {
            plant.advance(0.0, AMBIENT_C, pieceHeatW, PlantModifiers.neutral(), TICK);
        }

        assertThat(AMBIENT_C - plant.platenTempC()).isCloseTo(0.31, within(0.02));
    }

    @Test
    void heaterActsAfterTheTransportDelay() {
        run(plant, 1.0, 5.0);
        assertThat(plant.platenTempC()).isEqualTo(AMBIENT_C);

        run(plant, 1.0, 0.1);
        assertThat(plant.platenTempC()).isGreaterThan(AMBIENT_C);
    }

    @Test
    void sensorTipLagsThePlatenWhileHeating() {
        run(plant, 1.0, 60);

        assertThat(plant.sensorTipTempC()).isLessThan(plant.platenTempC()).isGreaterThan(AMBIENT_C);
    }

    @Test
    void modifiersScaleHeatingAndShiftAmbient() {
        ThermalPlant degraded = new ThermalPlant(DefaultParameters.thermal(), TICK);
        ThermalPlant coldFront = new ThermalPlant(DefaultParameters.thermal(), TICK);
        for (double t = 0; t < 10 * TAU_S; t += TICK.seconds()) {
            degraded.advance(1.0, AMBIENT_C, 0, PlantModifiers.neutral().withHeaterEfficiencyFactor(0.5), TICK);
            coldFront.advance(
                    1.0,
                    AMBIENT_C,
                    0,
                    PlantModifiers.neutral().withRthFactor(0.5).withAmbientDropC(10),
                    TICK);
        }

        assertThat(degraded.platenTempC()).isCloseTo(AMBIENT_C + DELTA_T_FULL_POWER_C / 2, within(0.5));
        assertThat(coldFront.platenTempC()).isCloseTo(AMBIENT_C - 10 + DELTA_T_FULL_POWER_C / 2, within(0.5));
    }

    @Test
    void transportDelayMustBeAWholeNumberOfTicks() {
        assertThatThrownBy(() -> new ThermalPlant(DefaultParameters.thermal(), new SimDuration(300)))
                .isInstanceOf(DomainException.class);
    }

    private double secondsToReach(double targetC) {
        ThermalPlant heating = new ThermalPlant(DefaultParameters.thermal(), TICK);
        double t = 0;
        while (heating.platenTempC() < targetC) {
            heating.advance(1.0, AMBIENT_C, 0, PlantModifiers.neutral(), TICK);
            t += TICK.seconds();
        }
        return t;
    }

    private static void run(ThermalPlant plant, double heaterFraction, double seconds) {
        long ticks = Math.round(seconds / TICK.seconds());
        for (long i = 0; i < ticks; i++) {
            plant.advance(heaterFraction, AMBIENT_C, 0, PlantModifiers.neutral(), TICK);
        }
    }

    private static ThermalParameters withInitialTemp(ThermalParameters p, double initialTempC) {
        return new ThermalParameters(
                p.heaterPowerW(),
                p.heaterEfficiency(),
                p.capacitanceJPerK(),
                p.rthKPerW(),
                p.transportDelay(),
                p.sensorTau(),
                initialTempC,
                p.pieceHeatJ());
    }
}
