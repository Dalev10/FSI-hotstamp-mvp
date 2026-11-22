package com.simex.twin.domain.plant;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.io.ValveCommand;
import com.simex.twin.domain.plant.PlantEvent.PieceArrived;
import com.simex.twin.domain.plant.PlantEvent.PieceDiscarded;
import com.simex.twin.domain.plant.PlantEvent.PieceExited;
import com.simex.twin.domain.plant.PlantEvent.StampContact;
import com.simex.twin.domain.plant.conveyor.ArrivalGenerator;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.plant.conveyor.ExitResult;
import com.simex.twin.domain.plant.conveyor.ExponentialArrivalGenerator;
import com.simex.twin.domain.plant.conveyor.Piece;
import com.simex.twin.domain.plant.conveyor.PieceType;
import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.RandomStreams;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimulatedPlantTest {

    private static final double AMBIENT_C = 25.0;
    private static final long SEED = 42;

    @Test
    void theQueueGrowsWhileTheBeltIsStopped() {
        SimulatedPlant plant = plant(randomArrivals(), PlantModifiers.neutral());
        List<PlantEvent> events = new ArrayList<>();
        for (int i = 0; i < 6_000; i++) { // 10 min con la banda detenida
            plant.writeOutputs(ProcessOutputs.SAFE);
            plant.advance(TICK);
            events.addAll(plant.drainEvents());
        }

        long arrived = events.stream().filter(PieceArrived.class::isInstance).count();
        assertThat(arrived).isGreaterThan(30);
        assertThat(plant.queueLength()).isEqualTo(arrived);
        assertThat(plant.piecesOnBelt()).isEmpty();
    }

    @Test
    void piecesNeverOverlapWhileFlowing() {
        SimulatedPlant plant = plant(randomArrivals(), PlantModifiers.neutral());
        ProcessOutputs running = outputs(0.7, false, false);
        int maxOnBelt = 0;
        for (int i = 0; i < 36_000; i++) { // 1 h
            plant.writeOutputs(running);
            plant.advance(TICK);
            List<Piece> pieces = plant.piecesOnBelt();
            for (int j = 1; j < pieces.size(); j++) {
                assertThat(pieces.get(j - 1).rearM())
                        .isGreaterThan(pieces.get(j).frontM());
            }
            maxOnBelt = Math.max(maxOnBelt, pieces.size());
        }

        assertThat(maxOnBelt).isGreaterThan(1);
    }

    /** Decisión 1 de la T4: con la cara del pistón en ±0,04 m, todo metal detectado se descarta en el rango de PWM. */
    @ParameterizedTest(name = "pwm {0}, fricción {1}")
    @CsvSource({"0.4, 1.3", "0.4, 1.0", "0.7, 1.0", "1.0, 1.0"})
    void everyDetectedMetalIsDiscarded(double pwm, double friction) {
        SimulatedPlant plant =
                plant(metalOnlyArrivals(), PlantModifiers.neutral().withFrictionFactor(friction));
        DiscardOnlyControl control = new DiscardOnlyControl(pwm);
        List<PlantEvent> events = new ArrayList<>();
        // 30 min, y después hasta que termine el ciclo de descarte en curso.
        for (int i = 0; i < 18_000 || control.phase != DiscardOnlyControl.Phase.RUN; i++) {
            plant.writeOutputs(control.scan(plant.readInputs()));
            plant.advance(TICK);
            events.addAll(plant.drainEvents());
        }

        long discarded =
                events.stream().filter(PieceDiscarded.class::isInstance).count();
        long escaped = events.stream()
                .filter(e -> e instanceof PieceExited exited && exited.result() == ExitResult.METAL_ESCAPED)
                .count();
        assertThat(control.detections).isGreaterThan(40);
        assertThat(discarded).isEqualTo(control.detections);
        // Solo escapan los que el inductivo no vio (p_miss ≈ 0,3–3,6 %).
        assertThat(escaped).isLessThan(control.detections / 10);
    }

    @Test
    void stampingTakesThePieceHeatOnceAndMarksItStamped() {
        SimulatedPlant plant = plant(onePiece(PieceType.PLASTIC), PlantModifiers.neutral());
        List<PlantEvent> events = runStampCycle(plant, 30); // mantiene el troquel 3 s, más que la permanencia de 1 s

        assertThat(events)
                .filteredOn(StampContact.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((StampContact) e).pieceId()).isEqualTo("P-000001"));
        assertThat(AMBIENT_C - plant.platenTempC()).isCloseTo(0.31, within(0.02));
        assertThat(exitResults(events)).containsExactly(ExitResult.STAMPED_OK);
    }

    @Test
    void aPlasticThatIsNeverStampedExitsUnstamped() {
        SimulatedPlant plant = plant(onePiece(PieceType.PLASTIC), PlantModifiers.neutral());
        List<PlantEvent> events = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            plant.writeOutputs(outputs(0.7, false, false));
            plant.advance(TICK);
            events.addAll(plant.drainEvents());
        }

        assertThat(exitResults(events)).containsExactly(ExitResult.UNSTAMPED);
    }

    @Test
    void pneumaticLeakModifiersSlowEachCylinder() {
        SimulatedPlant plant =
                plant(onePiece(PieceType.PLASTIC), PlantModifiers.neutral().withStrokeFactors(1.5, 5));
        ProcessOutputs energized = outputs(0, true, true);
        ProcessInputs atTick5 = null;
        for (int tick = 1; tick <= 6; tick++) {
            plant.writeOutputs(energized);
            plant.advance(TICK);
            if (tick == 5) {
                atTick5 = plant.readInputs();
            }
        }

        assertThat(atTick5.stampPosition()).isEqualTo(CylinderPosition.EXTENDING);
        assertThat(plant.readInputs().stampPosition()).isEqualTo(CylinderPosition.EXTENDED); // tick 6
        assertThat(plant.readInputs().discardPosition()).isEqualTo(CylinderPosition.EXTENDING); // ×5: 1750 ms
    }

    @Test
    void anArrivalOverrideReplacesTheBaseProfile() {
        SimulatedPlant plant =
                plant(randomArrivals(), PlantModifiers.neutral().withArrivalOverride(new ArrivalProfile(5, 1.0)));
        List<PlantEvent> events = new ArrayList<>();
        for (int i = 0; i < 6_000; i++) {
            plant.writeOutputs(ProcessOutputs.SAFE);
            plant.advance(TICK);
            events.addAll(plant.drainEvents());
        }

        assertThat(events)
                .filteredOn(PieceArrived.class::isInstance)
                .hasSizeGreaterThan(60) // media de 5 s en 10 min, no la base de 10 s
                .allSatisfy(e -> assertThat(((PieceArrived) e).type()).isEqualTo(PieceType.METAL));
    }

    @Test
    void powersFollowTheOutputsAndTheMotorWear() {
        SimulatedPlant plant =
                plant(onePiece(PieceType.PLASTIC), PlantModifiers.neutral().withFrictionFactor(1.3));
        plant.writeOutputs(
                new ProcessOutputs(true, new DutyCycle(0.7), ValveCommand.DE_ENERGIZED, ValveCommand.DE_ENERGIZED));

        assertThat(plant.heaterPowerW()).isEqualTo(1500);
        assertThat(plant.motorPowerW()).isCloseTo(100 * 0.7 * 1.3, within(1e-9));
    }

    static SimulatedPlant plant(ArrivalGenerator arrivals, PlantModifiers modifiers) {
        return new SimulatedPlant(
                DefaultParameters.plant(),
                new RandomStreams(SEED),
                arrivals,
                new ArrivalProfile(10, 0.1),
                ms -> AMBIENT_C,
                () -> modifiers,
                TICK);
    }

    /** Banda a velocidad nominal hasta la estación; troquel extendido {@code holdTicks}; luego hasta la salida. */
    private static List<PlantEvent> runStampCycle(SimulatedPlant plant, int holdTicks) {
        List<PlantEvent> events = new ArrayList<>();
        int held = 0;
        boolean stamped = false;
        for (int i = 0; i < 400; i++) {
            ProcessInputs in = plant.readInputs();
            boolean atStation = in.photoelectric() && !stamped;
            boolean holding = atStation && in.beltSpeedMps() == 0;
            if (holding && in.stampPosition() == CylinderPosition.EXTENDED && ++held >= holdTicks) {
                stamped = true;
            }
            plant.writeOutputs(outputs(atStation ? 0 : 0.7, false, holding && !stamped));
            plant.advance(TICK);
            events.addAll(plant.drainEvents());
        }
        return events;
    }

    private static List<ExitResult> exitResults(List<PlantEvent> events) {
        return events.stream()
                .filter(PieceExited.class::isInstance)
                .map(e -> ((PieceExited) e).result())
                .toList();
    }

    private static ProcessOutputs outputs(double pwm, boolean discard, boolean stamp) {
        return new ProcessOutputs(
                false,
                new DutyCycle(pwm),
                discard ? ValveCommand.ENERGIZED : ValveCommand.DE_ENERGIZED,
                stamp ? ValveCommand.ENERGIZED : ValveCommand.DE_ENERGIZED);
    }

    private static ArrivalGenerator randomArrivals() {
        RandomStreams streams = new RandomStreams(SEED);
        return new ExponentialArrivalGenerator(
                streams.get(RandomStreams.Name.ARRIVALS), streams.get(RandomStreams.Name.PIECE_TYPE));
    }

    private static ArrivalGenerator metalOnlyArrivals() {
        ArrivalGenerator random = randomArrivals();
        return (now, profile) -> random.arrivalsUntil(now, new ArrivalProfile(10, 1.0));
    }

    private static ArrivalGenerator onePiece(PieceType type) {
        boolean[] delivered = {false};
        return (now, profile) -> {
            if (delivered[0]) {
                return List.of();
            }
            delivered[0] = true;
            return List.of(type);
        };
    }

    /** Lo mínimo de la secuencia de descarte (4.6): frenar en el flanco del inductivo, extender detenida, retraer. */
    private static final class DiscardOnlyControl {

        private enum Phase {
            RUN,
            BRAKE,
            EXTEND,
            RETRACT
        }

        private final double pwm;
        private Phase phase = Phase.RUN;
        private boolean previousInductive;
        private long detections;

        DiscardOnlyControl(double pwm) {
            this.pwm = pwm;
        }

        ProcessOutputs scan(ProcessInputs in) {
            boolean risingEdge = in.inductive() && !previousInductive;
            previousInductive = in.inductive();
            phase = switch (phase) {
                case RUN -> risingEdge ? Phase.BRAKE : Phase.RUN;
                case BRAKE -> in.beltSpeedMps() == 0 ? Phase.EXTEND : Phase.BRAKE;
                case EXTEND -> in.discardPosition() == CylinderPosition.EXTENDED ? Phase.RETRACT : Phase.EXTEND;
                case RETRACT -> in.discardPosition() == CylinderPosition.RETRACTED ? Phase.RUN : Phase.RETRACT;
            };
            if (risingEdge) {
                detections++;
            }
            return outputs(phase == Phase.RUN ? pwm : 0, phase == Phase.EXTEND, false);
        }
    }
}
