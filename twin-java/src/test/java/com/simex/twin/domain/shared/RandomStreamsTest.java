package com.simex.twin.domain.shared;

import static com.simex.twin.domain.shared.RandomStreams.Name.AMBIENT_NOISE;
import static com.simex.twin.domain.shared.RandomStreams.Name.ARRIVALS;
import static com.simex.twin.domain.shared.RandomStreams.Name.INDUCTIVE_MISS;
import static com.simex.twin.domain.shared.RandomStreams.Name.PIECE_TYPE;
import static com.simex.twin.domain.shared.RandomStreams.Name.TC_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.shared.RandomStreams.Name;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RandomStreamsTest {

    private static final long SEED = 42;
    private static final int SAMPLES = 100_000;

    @Test
    void sameSeedGivesTheSameSequenceInEveryStream() {
        RandomStreams first = new RandomStreams(SEED);
        RandomStreams second = new RandomStreams(SEED);

        for (Name name : Name.values()) {
            assertThat(uniforms(first.get(name), 1_000))
                    .as(name.id())
                    .containsExactly(uniforms(second.get(name), 1_000));
        }
    }

    @Test
    void differentSeedsGiveDifferentSequences() {
        assertThat(uniforms(new RandomStreams(SEED).get(TC_NOISE), 10))
                .isNotEqualTo(uniforms(new RandomStreams(SEED + 1).get(TC_NOISE), 10));
    }

    @Test
    void drawingFromOneStreamDoesNotAlterTheOthers() {
        RandomStreams untouched = new RandomStreams(SEED);
        RandomStreams disturbed = new RandomStreams(SEED);
        uniforms(disturbed.get(ARRIVALS), 10_000);
        gaussians(disturbed.get(PIECE_TYPE), 10_000);

        for (Name name : List.of(INDUCTIVE_MISS, TC_NOISE, AMBIENT_NOISE)) {
            assertThat(uniforms(disturbed.get(name), 1_000))
                    .as(name.id())
                    .containsExactly(uniforms(untouched.get(name), 1_000));
        }
    }

    @Test
    void streamsAreUncorrelated() {
        RandomStreams streams = new RandomStreams(SEED);
        Map<Name, double[]> samples = new EnumMap<>(Name.class);
        for (Name name : Name.values()) {
            samples.put(name, uniforms(streams.get(name), SAMPLES));
        }

        Name[] names = Name.values();
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                assertThat(Math.abs(correlation(samples.get(names[i]), samples.get(names[j]))))
                        .as("%s vs %s", names[i], names[j])
                        .isLessThan(0.01);
            }
        }
    }

    @Test
    void everyStreamGetsADistinctSeed() {
        assertThat(Arrays.stream(Name.values())
                        .map(name -> RandomStreams.streamSeed(SEED, name.id()))
                        .distinct())
                .hasSize(Name.values().length);
    }

    @Test
    void sameNameReturnsTheSameStatefulInstance() {
        RandomStreams streams = new RandomStreams(SEED);

        assertThat(streams.get(TC_NOISE)).isSameAs(streams.get(TC_NOISE));
    }

    @Test
    void uniformStaysInTheUnitInterval() {
        double[] samples = uniforms(new RandomStreams(SEED).get(ARRIVALS), SAMPLES);

        assertThat(Arrays.stream(samples).allMatch(u -> u >= 0 && u < 1)).isTrue();
        assertThat(mean(samples)).isCloseTo(0.5, within(0.01));
    }

    @Test
    void gaussianHasZeroMeanAndUnitSigma() {
        double[] samples = gaussians(new RandomStreams(SEED).get(TC_NOISE), SAMPLES);

        assertThat(mean(samples)).isCloseTo(0.0, within(0.01));
        assertThat(standardDeviation(samples)).isCloseTo(1.0, within(0.01));
    }

    /**
     * Valores calculados con una implementación independiente en Python. Si esta prueba falla, cambió el algoritmo y con
     * él todas las corridas y el golden: no se actualiza sin avisar.
     */
    @Test
    void referenceValuesArePinned() {
        assertThat(RandomStreams.fnv1a64("arrivals")).isEqualTo(-5689240207299982253L);
        assertThat(Arrays.stream(Name.values()).map(name -> RandomStreams.streamSeed(SEED, name.id())))
                .containsExactly(
                        1426342262224226269L,
                        4129792452741349073L,
                        3384871810309949670L,
                        3835791692765839262L,
                        1422302926925671006L);

        assertThat(uniforms(new RandomStreams(SEED).get(TC_NOISE), 3))
                .containsExactly(0.19401405305205643, 0.7616137088113498, 0.2759020379278271);

        RandomStream ambientNoise = new RandomStreams(SEED).get(AMBIENT_NOISE);
        assertThat(ambientNoise.nextGaussian()).isCloseTo(-0.8699954229247531, within(1e-12));
        assertThat(ambientNoise.nextGaussian()).isCloseTo(1.8373428400235612, within(1e-12));
    }

    private static double[] uniforms(RandomStream stream, int count) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = stream.nextDouble();
        }
        return values;
    }

    private static double[] gaussians(RandomStream stream, int count) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = stream.nextGaussian();
        }
        return values;
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElseThrow();
    }

    private static double standardDeviation(double[] values) {
        double mean = mean(values);
        return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / values.length);
    }

    private static double correlation(double[] xs, double[] ys) {
        double meanX = mean(xs);
        double meanY = mean(ys);
        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (int i = 0; i < xs.length; i++) {
            covariance += (xs[i] - meanX) * (ys[i] - meanY);
            varianceX += (xs[i] - meanX) * (xs[i] - meanX);
            varianceY += (ys[i] - meanY) * (ys[i] - meanY);
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }
}
