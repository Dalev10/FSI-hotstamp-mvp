package com.simex.twin.domain.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FaultsTest {

    private static final PlantModifiers NEUTRAL = PlantModifiers.neutral();

    /** Contrato de {@link Fault}: las que tienen rampa empiezan neutrales; todas son deterministas. */
    @ParameterizedTest
    @EnumSource(
            value = FaultType.class,
            names = {"HEATER_DEGRADATION", "COLD_FRONT", "OFFSET_DRIFT", "MOTOR_WEAR"})
    void rampedFaultsStartNeutral(FaultType type) {
        assertThat(create(type).contribute(NEUTRAL, SimDuration.ZERO)).isEqualTo(NEUTRAL);
    }

    @ParameterizedTest
    @EnumSource(FaultType.class)
    void contributionIsAPureFunctionOfTime(FaultType type) {
        Fault fault = create(type);
        SimDuration at = new SimDuration(300_000);

        assertThat(fault.contribute(NEUTRAL, at)).isEqualTo(create(type).contribute(NEUTRAL, at));
    }

    @Test
    void eachFaultMovesItsKnob() {
        SimDuration halfway = new SimDuration(900_000);
        SimDuration end = new SimDuration(3_600_000);

        assertThat(create(FaultType.HEATER_DEGRADATION)
                        .contribute(NEUTRAL, halfway)
                        .heaterEfficiencyFactor())
                .isCloseTo(0.85, within(1e-12));
        assertThat(create(FaultType.HEATER_DEGRADATION).contribute(NEUTRAL, end).heaterEfficiencyFactor())
                .isCloseTo(0.70, within(1e-12));
        PlantModifiers coldFront = create(FaultType.COLD_FRONT).contribute(NEUTRAL, end);
        assertThat(coldFront.ambientDropC()).isEqualTo(10.0);
        assertThat(coldFront.rthFactor()).isEqualTo(0.85);
        assertThat(create(FaultType.SENSOR_NOISE)
                        .contribute(NEUTRAL, SimDuration.ZERO)
                        .noiseSigmaFactor())
                .isEqualTo(5.0);
        assertThat(create(FaultType.OFFSET_DRIFT)
                        .contribute(NEUTRAL, new SimDuration(60_000))
                        .offsetDriftMv())
                .isCloseTo(0.02, within(1e-12));
        assertThat(create(FaultType.THERMOCOUPLE_OPEN)
                        .contribute(NEUTRAL, SimDuration.ZERO)
                        .thermocoupleOpen())
                .isTrue();
        assertThat(create(FaultType.MOTOR_WEAR).contribute(NEUTRAL, end).frictionFactor())
                .isEqualTo(1.30);
        assertThat(create(FaultType.METAL_BURST)
                        .contribute(NEUTRAL, SimDuration.ZERO)
                        .arrivalOverride())
                .hasValue(new ArrivalProfile(5, 0.8));
    }

    @Test
    void aPneumaticLeakSlowsOnlyTheChosenCylinder() {
        PlantModifiers stampOnly = FaultFactory.create(FaultType.PNEUMATIC_LEAK, Map.of("cylinder", "STAMP"))
                .contribute(NEUTRAL, SimDuration.ZERO);
        PlantModifiers both = FaultFactory.create(
                        FaultType.PNEUMATIC_LEAK, Map.of("cylinder", "BOTH", "strokeFactor", 5.0))
                .contribute(NEUTRAL, SimDuration.ZERO);

        assertThat(stampOnly.stampStrokeFactor()).isEqualTo(1.5);
        assertThat(stampOnly.discardStrokeFactor()).isEqualTo(1.0);
        assertThat(both.stampStrokeFactor()).isEqualTo(5.0);
        assertThat(both.discardStrokeFactor()).isEqualTo(5.0);
    }

    @Test
    void theFactoryAppliesDefaultsAndRejectsBadParameters() {
        assertThat(FaultFactory.create(FaultType.COLD_FRONT, Map.of("ambientDropC", 4.0))
                        .params())
                .containsExactly(
                        Map.entry("ambientDropC", 4.0), Map.entry("rthFactor", 0.85), Map.entry("rampS", 600.0));
        assertThatThrownBy(() -> FaultFactory.create(FaultType.SENSOR_NOISE, Map.of("sigma", 2.0)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FaultFactory.create(FaultType.SENSOR_NOISE, Map.of("sigmaFactor", "alto")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FaultFactory.create(FaultType.PNEUMATIC_LEAK, Map.of()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FaultFactory.create(FaultType.PNEUMATIC_LEAK, Map.of("cylinder", "AMBOS")))
                .isInstanceOf(DomainException.class);
        // Los rangos del contrato (fault.schema.json).
        assertThatThrownBy(() -> FaultFactory.create(FaultType.COLD_FRONT, Map.of("rthFactor", 0.0)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () -> FaultFactory.create(FaultType.HEATER_DEGRADATION, Map.of("finalEfficiencyFactor", 1.1)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FaultFactory.create(FaultType.MOTOR_WEAR, Map.of("rampS", -1.0)))
                .isInstanceOf(DomainException.class);
        assertThat(FaultFactory.create(FaultType.METAL_BURST, Map.of("metalRatio", 0.0))
                        .params())
                .containsEntry("metalRatio", 0.0);
        assertThat(EnumSet.allOf(FaultType.class))
                .allSatisfy(t -> assertThat(FaultFactory.paramNames(t)).isNotNull());
    }

    @Test
    void theInjectorComposesFaultsMultiplyingFactorsAndAddingOffsets() {
        FaultInjector injector = new FaultInjector(42);
        injector.inject(fault(FaultType.SENSOR_NOISE), Optional.empty(), SimTime.ZERO);
        injector.inject(fault(FaultType.SENSOR_NOISE), Optional.empty(), SimTime.ZERO);
        injector.inject(fault(FaultType.OFFSET_DRIFT), Optional.empty(), SimTime.ZERO);
        injector.inject(fault(FaultType.OFFSET_DRIFT), Optional.empty(), SimTime.ZERO);
        injector.update(new SimTime(60_000));

        assertThat(injector.current().noiseSigmaFactor()).isEqualTo(25.0);
        assertThat(injector.current().offsetDriftMv()).isCloseTo(0.04, within(1e-12));
    }

    @Test
    void faultsExpireAfterTheirDurationAndAMetalBurstLasts120SecondsByDefault() {
        FaultInjector injector = new FaultInjector(42);
        injector.inject(fault(FaultType.SENSOR_NOISE), Optional.of(new SimDuration(30_000)), SimTime.ZERO);
        FaultInjector.ActiveFault burst = injector.inject(fault(FaultType.METAL_BURST), Optional.empty(), SimTime.ZERO);
        assertThat(burst.duration()).hasValue(new SimDuration(120_000));

        assertThat(injector.update(new SimTime(29_900))).isEmpty();
        assertThat(injector.update(new SimTime(30_000)))
                .extracting(f -> f.fault().type())
                .containsExactly(FaultType.SENSOR_NOISE);
        assertThat(injector.update(new SimTime(120_000)))
                .extracting(f -> f.fault().type())
                .containsExactly(FaultType.METAL_BURST);
        assertThat(injector.current()).isEqualTo(NEUTRAL);
    }

    @Test
    void removingAFaultRestoresTheKnobsAndIdsAreReproducible() {
        FaultInjector first = new FaultInjector(42);
        FaultInjector second = new FaultInjector(42);
        FaultInjector.ActiveFault a = first.inject(fault(FaultType.THERMOCOUPLE_OPEN), Optional.empty(), SimTime.ZERO);
        FaultInjector.ActiveFault b = second.inject(fault(FaultType.THERMOCOUPLE_OPEN), Optional.empty(), SimTime.ZERO);
        assertThat(a.faultId()).isEqualTo(b.faultId()).matches("[0-9a-f-]{36}");
        assertThat(new FaultInjector(7).inject(fault(FaultType.THERMOCOUPLE_OPEN), Optional.empty(), SimTime.ZERO))
                .extracting(FaultInjector.ActiveFault::faultId)
                .isNotEqualTo(a.faultId());

        assertThat(first.remove(a.faultId())).isPresent();
        assertThat(first.remove(a.faultId())).isEmpty();
        assertThat(first.current()).isEqualTo(NEUTRAL);
        assertThat(first.active()).isEmpty();
    }

    @Test
    void activeFaultsKeepInjectionOrder() {
        FaultInjector injector = new FaultInjector(42);
        for (FaultType type : List.of(FaultType.COLD_FRONT, FaultType.SENSOR_NOISE, FaultType.MOTOR_WEAR)) {
            injector.inject(fault(type), Optional.empty(), SimTime.ZERO);
        }

        assertThat(injector.active())
                .extracting(f -> f.fault().type())
                .containsExactly(FaultType.COLD_FRONT, FaultType.SENSOR_NOISE, FaultType.MOTOR_WEAR);
    }

    private static Fault create(FaultType type) {
        return fault(type);
    }

    private static Fault fault(FaultType type) {
        return FaultFactory.create(type, type == FaultType.PNEUMATIC_LEAK ? Map.of("cylinder", "BOTH") : Map.of());
    }
}
