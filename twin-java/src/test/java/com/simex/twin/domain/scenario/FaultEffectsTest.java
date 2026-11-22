package com.simex.twin.domain.scenario;

import static com.simex.twin.domain.scenario.TestScenarios.fault;
import static com.simex.twin.domain.scenario.TestScenarios.idle;
import static com.simex.twin.domain.scenario.TestScenarios.run;
import static com.simex.twin.domain.scenario.TestScenarios.runner;
import static com.simex.twin.domain.scenario.TestScenarios.started;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.machine.MachineSnapshot;
import com.simex.twin.domain.machine.ScanResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cada falla produce su efecto (anexo A y tabla 4.10) a través de la máquina completa. */
class FaultEffectsTest {

    @Test
    void motorWearSlowsTheBeltTo0point1346() {
        ScenarioRunner runner = runner(started(List.of(fault(0, FaultType.MOTOR_WEAR, Map.of()))));
        double[] maxSpeedMps = {0};
        run(
                runner,
                620,
                700,
                r -> maxSpeedMps[0] =
                        Math.max(maxSpeedMps[0], r.snapshot().control().inputs().beltSpeedMps()));

        assertThat(maxSpeedMps[0]).isCloseTo(0.1346, within(0.01 * 0.1346));
    }

    @Test
    void anOpenThermocoupleFaultsTheMachineByItself() {
        ScenarioRunner runner = runner(started(List.of(fault(300, FaultType.THERMOCOUPLE_OPEN, Map.of()))));
        MachineSnapshot end = run(runner, 301).getLast().snapshot();

        assertThat(end.control().state()).isEqualTo(MachineState.FAULT);
        assertThat(end.control().interlock()).hasValue(AlarmCode.THERMOCOUPLE_OPEN);
        assertThat(end.control().inputs().tcCounts()).isEqualTo(4095);
    }

    @Test
    void aFivefoldLeakTripsThePneumaticTimeout() {
        ScenarioRunner runner = runner(
                started(List.of(fault(0, FaultType.PNEUMATIC_LEAK, Map.of("cylinder", "STAMP", "strokeFactor", 5.0)))));
        MachineSnapshot end = run(runner, 600).getLast().snapshot();

        assertThat(end.control().state()).isEqualTo(MachineState.FAULT);
        assertThat(end.control().activeAlarms().getFirst().code()).isEqualTo(AlarmCode.PNEUMATIC_TIMEOUT);
        assertThat(end.control().activeAlarms().getFirst().detail()).hasValue("STAMP");
    }

    @Test
    void offsetDriftRaisesTheMeasurementAboutHalfADegreePerMinute() {
        ScenarioRunner runner = runner(idle(List.of(fault(0, FaultType.OFFSET_DRIFT, Map.of()))));
        List<Double> measured = new ArrayList<>();
        run(runner, 598, 602, r -> measured.add(r.snapshot().control().measuredTempC()));

        // Troquel frío a 25 °C; 10 min · 0,02 mV/min / 0,041 mV/°C = 4,88 °C de error.
        assertThat(mean(measured)).isCloseTo(25 + 10 * 0.02 / 0.041, within(0.3));
        assertThat(runner.machine().snapshot().groundTruth().platenTempC()).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void sensorNoiseMultipliesTheMeasurementSigmaByFive() {
        // En caliente: con el troquel a la temperatura ambiente el amplificador recorta el ruido negativo en 0 V.
        ScenarioRunner runner = runner(started(List.of(fault(0, FaultType.SENSOR_NOISE, Map.of()))));
        List<Double> residuals = new ArrayList<>();
        run(
                runner,
                400,
                700,
                r -> residuals.add(r.snapshot().control().measuredTempC()
                        - r.snapshot().groundTruth().platenTempC()));

        assertThat(sigma(residuals)).isCloseTo(0.3 * 5, within(0.15));
    }

    @Test
    void heaterDegradationEndsAt66point5PercentEfficiency() {
        ScenarioRunner runner = runner(idle(List.of(fault(0, FaultType.HEATER_DEGRADATION, Map.of()))));
        MachineSnapshot end = run(runner, 1801).getLast().snapshot();

        assertThat(end.groundTruth().heaterEfficiency()).isCloseTo(0.95 * 0.70, within(1e-9));
    }

    @Test
    void aColdFrontDropsTheRealAmbientTenDegrees() {
        ScenarioRunner runner = runner(idle(List.of(fault(0, FaultType.COLD_FRONT, Map.of()))));
        MachineSnapshot end = run(runner, 601).getLast().snapshot();

        assertThat(end.groundTruth().ambientC()).isCloseTo(15.0, within(1e-9));
    }

    @Test
    void aMetalBurstDoublesTheArrivalsForTwoMinutesAndThenClears() {
        List<ScanResult> burst = run(runner(idle(List.of(fault(0, FaultType.METAL_BURST, Map.of())))), 121);
        List<ScanResult> normal = run(runner(idle(List.of())), 121);

        assertThat(burst.getLast().snapshot().counters().piecesArrived())
                .isGreaterThan(normal.getLast().snapshot().counters().piecesArrived());
        assertThat(burst.stream().flatMap(r -> r.events().stream()))
                .filteredOn(DomainEvent.FaultCleared.class::isInstance)
                .hasSize(1);
        assertThat(burst.getLast().snapshot().groundTruth().activeFaults()).isEmpty();
    }

    @Test
    void injectionIsReportedWithTheEffectiveParametersAndListedAsActive() {
        List<ScanResult> results = run(runner(idle(List.of(fault(5, FaultType.COLD_FRONT, Map.of())))), 6);
        DomainEvent.FaultInjected injected = results.stream()
                .flatMap(r -> r.events().stream())
                .filter(DomainEvent.FaultInjected.class::isInstance)
                .map(DomainEvent.FaultInjected.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(injected.params()).containsEntry("ambientDropC", 10.0).containsEntry("rampS", 600.0);
        assertThat(injected.durationS()).isEmpty();
        MachineSnapshot.ActiveFault active =
                results.getLast().snapshot().groundTruth().activeFaults().getFirst();
        assertThat(active.faultId()).isEqualTo(injected.faultId());
        assertThat(active.since().ms()).isEqualTo(5_000);
    }

    @Test
    void removingAFaultClearsItAndRestoresThePlant() {
        ScenarioRunner runner = runner(idle(List.of(fault(0, FaultType.THERMOCOUPLE_OPEN, Map.of()))));
        run(runner, 1);
        String faultId = runner.machine()
                .snapshot()
                .groundTruth()
                .activeFaults()
                .getFirst()
                .faultId();

        assertThat(runner.machine().removeFault(faultId)).isPresent();
        assertThat(runner.machine().removeFault("inexistente")).isEmpty();
        ScanResult next = runner.step(runner.now().ms());

        assertThat(next.events()).contains(new DomainEvent.FaultCleared(faultId, FaultType.THERMOCOUPLE_OPEN));
        assertThat(next.snapshot().groundTruth().activeFaults()).isEmpty();
        assertThat(next.snapshot().control().inputs().tcCounts()).isLessThan(4090);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double sigma(List<Double> values) {
        double mean = mean(values);
        double[] squares =
                values.stream().mapToDouble(v -> (v - mean) * (v - mean)).toArray();
        return Math.sqrt(Arrays.stream(squares).average().orElseThrow());
    }
}
