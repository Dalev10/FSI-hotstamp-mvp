package com.simex.twin.domain;

import static com.simex.twin.domain.DefaultParameters.DEFAULT_SETPOINT_C;
import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.control.thermal.ControllerInput;
import com.simex.twin.domain.control.thermal.LegacyOnOffController;
import com.simex.twin.domain.control.thermal.PidController;
import com.simex.twin.domain.control.thermal.TemperatureController;
import com.simex.twin.domain.control.thermal.ThermocoupleFirmware;
import com.simex.twin.domain.control.thermal.TimeProportionalSsr;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.plant.SimulatedPlant;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.Percent;
import com.simex.twin.domain.shared.RandomStreams;
import com.simex.twin.domain.shared.SimTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lazo cerrado de 1 h contra la planta simulada sin piezas (sección 9.2). Las métricas usan la temperatura medida
 * desde el fin del calentamiento: la primera vez que la medida alcanza el setpoint (PID) o el umbral (LEGACY).
 */
class ClosedLoopThermalTest {

    private static final int ONE_HOUR_TICKS = 36_000;
    private static final double WINDOW_MIN_C = 50.0;
    private static final double WINDOW_MAX_C = 75.0;

    @Test
    void pidHoldsTheWindowAndTheSetpointWithLittleOvershoot() {
        LoopResult pid = run(new PidController(DefaultParameters.pid(), DEFAULT_SETPOINT_C), true, DEFAULT_SETPOINT_C);

        assertThat(pid.measuredInWindowFraction()).isEqualTo(1.0);
        assertThat(pid.realInWindowFraction()).isEqualTo(1.0);
        assertThat(Math.abs(pid.meanMeasuredC() - DEFAULT_SETPOINT_C)).isLessThan(0.5);
        assertThat(pid.maxRealC() - DEFAULT_SETPOINT_C).as("sobrepico real").isLessThan(2.0);
    }

    @Test
    void legacyOscillatesAroundFiftyAndSpendsLessTimeInWindowThanPid() {
        LoopResult legacy = run(new LegacyOnOffController(DefaultParameters.legacy()), false, WINDOW_MIN_C);
        LoopResult pid = run(new PidController(DefaultParameters.pid(), DEFAULT_SETPOINT_C), true, DEFAULT_SETPOINT_C);

        assertThat(legacy.meanMeasuredC()).isBetween(49.0, 52.0);
        assertThat(legacy.measuredInWindowFraction()).isLessThan(pid.measuredInWindowFraction());
    }

    /**
     * @param viaSsrWindow PID pasa por la ventana proporcional del SSR; LEGACY lo maneja directo (decisión 2 de la T5)
     * @param heatupDoneC temperatura medida que marca el fin del calentamiento
     */
    private static LoopResult run(TemperatureController controller, boolean viaSsrWindow, double heatupDoneC) {
        SimulatedPlant plant = new SimulatedPlant(
                DefaultParameters.plant(),
                new RandomStreams(42),
                (now, profile) -> List.of(),
                new ArrivalProfile(10, 0.1),
                ms -> 25.0,
                PlantModifiers::neutral,
                TICK);
        ThermocoupleFirmware firmware = new ThermocoupleFirmware(DefaultParameters.firmware());
        TimeProportionalSsr ssr = new TimeProportionalSsr(DefaultParameters.SSR_WINDOW, TICK);
        LoopResult result = new LoopResult();
        for (long tick = 0; tick < ONE_HOUR_TICKS; tick++) {
            ProcessInputs in = plant.readInputs();
            double measuredC = firmware.temperatureC(in.tcCounts(), in.ambientC());
            Percent output = controller.compute(new ControllerInput(
                    new SimTime(tick * TICK.ms()), measuredC, true, in.photoelectric(), in.inductive()));
            boolean ssrOn = viaSsrWindow ? ssr.update(output.toDutyCycle()) : output.value() >= Percent.MAX;
            plant.writeOutputs(
                    new ProcessOutputs(ssrOn, DutyCycle.ZERO, ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED));
            plant.advance(TICK);
            result.record(measuredC, plant.platenTempC(), heatupDoneC);
        }
        return result;
    }

    private static final class LoopResult {

        private boolean heatedUp;
        private long samples;
        private long measuredInWindow;
        private long realInWindow;
        private double measuredSumC;
        private double maxRealC = Double.NEGATIVE_INFINITY;

        void record(double measuredC, double realC, double heatupDoneC) {
            maxRealC = Math.max(maxRealC, realC);
            heatedUp |= measuredC >= heatupDoneC;
            if (!heatedUp) {
                return;
            }
            samples++;
            measuredSumC += measuredC;
            measuredInWindow += inWindow(measuredC) ? 1 : 0;
            realInWindow += inWindow(realC) ? 1 : 0;
        }

        double measuredInWindowFraction() {
            return (double) measuredInWindow / samples;
        }

        double realInWindowFraction() {
            return (double) realInWindow / samples;
        }

        double meanMeasuredC() {
            return measuredSumC / samples;
        }

        double maxRealC() {
            return maxRealC;
        }

        private static boolean inWindow(double tempC) {
            return WINDOW_MIN_C <= tempC && tempC <= WINDOW_MAX_C;
        }
    }
}
