package com.simex.twin.domain.plant.conveyor;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.RandomStreams;
import com.simex.twin.domain.shared.SimTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Motor, sensores, inspección de salida y llegadas; incluye las filas de banda del anexo A. */
class ConveyorComponentsTest {

    private final ConveyorParameters parameters = DefaultParameters.conveyor();

    @ParameterizedTest(name = "pwm {0}, fricción {1} → {2} m/s")
    @CsvSource({"0.7, 1.0, 0.175", "0.7, 1.3, 0.1346"})
    void beltReachesItsTargetSpeed(double pwm, double friction, double expectedMps) {
        Motor motor = motor();
        for (int i = 0; i < 20; i++) {
            motor.advance(new DutyCycle(pwm), friction, TICK);
        }

        assertThat(motor.speedMps()).isCloseTo(expectedMps, within(0.01 * expectedMps));
    }

    @Test
    void brakingDistanceIsExactWhereverTheTickFalls() {
        Motor motor = motor();
        for (int i = 0; i < 20; i++) {
            motor.advance(new DutyCycle(0.7), 1.0, TICK);
        }
        double brakingM = 0;
        for (int i = 0; i < 5; i++) {
            brakingM += motor.advance(DutyCycle.ZERO, 1.0, TICK);
        }

        assertThat(motor.speedMps()).isZero();
        assertThat(brakingM).isCloseTo(0.175 * 0.175 / (2 * 2.0), within(1e-12)); // v² / 2a
    }

    @Test
    void motorPowerGrowsWithFriction() {
        assertThat(motor().powerW(new DutyCycle(0.7), 1.3)).isCloseTo(100 * 0.7 * 1.3, within(1e-9));
    }

    @ParameterizedTest(name = "v = {0} m/s → p_miss = {1}")
    @CsvSource({"0.175, 0.0113", "0.25, 0.0363", "0.10, 0.0030"})
    void inductiveMissProbabilityMatchesTheAnnex(double speedMps, double expected) {
        InductiveSensor sensor =
                new InductiveSensor(parameters, new RandomStreams(42).get(RandomStreams.Name.INDUCTIVE_MISS));

        assertThat(sensor.missProbability(speedMps)).isCloseTo(expected, within(0.00005));
    }

    @Test
    void exitInspectionClassifiesEveryPiece() {
        ExitInspection inspection = new ExitInspection(parameters.exitQaAtM());
        Piece plastic = new Piece("P-000001", PieceType.PLASTIC, 0.06);
        Piece stampedPlastic = new Piece("P-000002", PieceType.PLASTIC, 0.06);
        stampedPlastic.markStamped();
        Piece stampedMetal = new Piece("P-000003", PieceType.METAL, 0.06);
        stampedMetal.markStamped();

        assertThat(inspection.inspect(stampedPlastic)).isEqualTo(ExitResult.STAMPED_OK);
        assertThat(inspection.inspect(plastic)).isEqualTo(ExitResult.UNSTAMPED);
        assertThat(inspection.inspect(stampedMetal)).isEqualTo(ExitResult.METAL_ESCAPED);
    }

    @Test
    void pieceOverlapIncludesItsEdges() {
        Piece piece = new Piece("P-000001", PieceType.PLASTIC, 0.06); // ocupa [0, 0.06]

        assertThat(piece.overlaps(0.0)).isTrue();
        assertThat(piece.overlaps(0.06)).isTrue();
        assertThat(piece.overlaps(0.0601)).isFalse();
        assertThat(piece.centerM()).isCloseTo(0.03, within(1e-12));
    }

    @Test
    void arrivalsFollowTheProfileOnAverage() {
        ExponentialArrivalGenerator generator = generator();
        ArrivalProfile profile = new ArrivalProfile(1.0, 0.3);
        int total = 0;
        int metal = 0;
        long horizonMs = 10_000_000; // 10⁴ s ≈ 10⁴ llegadas
        for (long t = TICK.ms(); t <= horizonMs; t += TICK.ms()) {
            for (PieceType type : generator.arrivalsUntil(new SimTime(t), profile)) {
                total++;
                metal += type == PieceType.METAL ? 1 : 0;
            }
        }

        assertThat(horizonMs / 1000.0 / total).isCloseTo(1.0, within(0.03));
        assertThat((double) metal / total).isCloseTo(0.3, within(0.015));
    }

    @Test
    void anOverrideProfileTakesEffectForTheNextArrivals() {
        ExponentialArrivalGenerator generator = generator();
        generator.arrivalsUntil(new SimTime(60_000), new ArrivalProfile(5.0, 0.0));

        assertThat(generator.arrivalsUntil(new SimTime(600_000), new ArrivalProfile(5.0, 1.0)))
                .isNotEmpty()
                .containsOnly(PieceType.METAL);
    }

    private Motor motor() {
        return new Motor(parameters.nominalSpeedMps(), parameters.accelMps2(), parameters.motorRatedW());
    }

    private static ExponentialArrivalGenerator generator() {
        RandomStreams streams = new RandomStreams(42);
        return new ExponentialArrivalGenerator(
                streams.get(RandomStreams.Name.ARRIVALS), streams.get(RandomStreams.Name.PIECE_TYPE));
    }
}
