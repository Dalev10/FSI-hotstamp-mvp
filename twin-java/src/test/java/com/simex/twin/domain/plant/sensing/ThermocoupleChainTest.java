package com.simex.twin.domain.plant.sensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.RandomStream;
import com.simex.twin.domain.shared.RandomStreams;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Filas de medición del anexo A, del lado de la planta (cuentas del ADC); la conversión a °C la prueba el firmware. */
class ThermocoupleChainTest {

    private static final double AMBIENT_C = 25.0;
    private static final PlantModifiers NEUTRAL = PlantModifiers.neutral();

    @Test
    void reading62point5WithoutNoiseOrOffsetGives477Counts() {
        assertThat(chain(0.0, 0.0).measure(62.5, AMBIENT_C, NEUTRAL)).isEqualTo(477);
    }

    @Test
    void anInputOffsetOfPoint01MillivoltGives480Counts() {
        assertThat(chain(0.01, 0.0).measure(62.5, AMBIENT_C, NEUTRAL)).isEqualTo(480);
    }

    @Test
    void offsetDriftFromAFaultAddsLikeTheInputOffset() {
        PlantModifiers drift = new PlantModifiers(1, 1, 0, 0.01, 1, false);

        assertThat(chain(0.0, 0.0).measure(62.5, AMBIENT_C, drift)).isEqualTo(480);
    }

    @Test
    void openThermocoupleSaturatesTheAdc() {
        PlantModifiers open = new PlantModifiers(1, 1, 0, 0, 1, true);

        assertThat(chain(0.0, 0.0).measure(62.5, AMBIENT_C, open)).isEqualTo(4095);
    }

    @Test
    void coldJunctionFollowsTheRealAmbient() {
        PlantModifiers coldFront = new PlantModifiers(1, 1, 10, 0, 1, false);

        // Con el ambiente real a 15 °C, 52,5 °C dan la misma tensión que 62,5 °C con 25 °C.
        assertThat(chain(0.0, 0.0).measure(52.5, AMBIENT_C, coldFront)).isEqualTo(477);
    }

    @Test
    void noiseHasTheConfiguredSigmaScaledByTheFaultFactor() {
        ThermocoupleChain noisy = chain(0.0, 0.3);
        PlantModifiers fiveTimes = new PlantModifiers(1, 1, 0, 0, 5, false);
        double lsbC = 3.3 / 4095 / 250 / 0.041 * 1000;

        double[] counts = new double[20_000];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = noisy.measure(62.5, AMBIENT_C, fiveTimes);
        }
        double mean = Arrays.stream(counts).average().orElseThrow();
        double sigmaCounts = Math.sqrt(Arrays.stream(counts)
                .map(c -> (c - mean) * (c - mean))
                .average()
                .orElseThrow());

        assertThat(sigmaCounts * lsbC).isCloseTo(0.3 * 5, within(0.03));
        assertThat(mean).isCloseTo(477, within(1.0));
    }

    @Test
    void ambientSensorReadsTheRealAmbientWithItsNoise() {
        AmbientSensor sensor = new AmbientSensor(0.1, stream());
        PlantModifiers coldFront = new PlantModifiers(1, 1, 10, 0, 1, false);

        double[] readings = new double[20_000];
        for (int i = 0; i < readings.length; i++) {
            readings[i] = sensor.read(AMBIENT_C, coldFront);
        }
        double mean = Arrays.stream(readings).average().orElseThrow();
        double sigma = Math.sqrt(Arrays.stream(readings)
                .map(r -> (r - mean) * (r - mean))
                .average()
                .orElseThrow());

        assertThat(mean).isCloseTo(15.0, within(0.005));
        assertThat(sigma).isCloseTo(0.1, within(0.002));
    }

    @Test
    void amplifierClampsBetweenZeroAndVref() {
        AmplifierStage amplifier = new AmplifierStage(250, 3.3);

        assertThat(amplifier.apply(1.5375, NEUTRAL)).isCloseTo(0.384375, within(1e-12));
        assertThat(amplifier.apply(-1.0, NEUTRAL)).isZero();
        assertThat(amplifier.apply(100.0, NEUTRAL)).isEqualTo(3.3);
    }

    @Test
    void adcRoundsAndClampsToItsRange() {
        Adc adc = new Adc(12, 3.3);

        assertThat(adc.convert(0.0)).isZero();
        assertThat(adc.convert(3.3)).isEqualTo(4095);
        assertThat(adc.convert(5.0)).isEqualTo(4095);
        assertThat(adc.convert(-0.1)).isZero();
        assertThat(adc.convert(0.384375)).isEqualTo(477);
    }

    private static ThermocoupleChain chain(double inputOffsetMv, double noiseSigmaC) {
        SensingParameters d = DefaultParameters.sensing();
        return new ThermocoupleChain(
                new SensingParameters(
                        d.seebeckMvPerC(),
                        d.amplifierGain(),
                        d.adcBits(),
                        d.adcVrefV(),
                        inputOffsetMv,
                        noiseSigmaC,
                        d.ambientNoiseSigmaC()),
                stream());
    }

    private static RandomStream stream() {
        return new RandomStreams(42).get(RandomStreams.Name.TC_NOISE);
    }
}
