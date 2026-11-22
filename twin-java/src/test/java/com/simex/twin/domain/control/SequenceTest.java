package com.simex.twin.domain.control;

import static com.simex.twin.domain.control.sequence.MachineState.DISCARD;
import static com.simex.twin.domain.control.sequence.MachineState.HEATING;
import static com.simex.twin.domain.control.sequence.MachineState.OFF;
import static com.simex.twin.domain.control.sequence.MachineState.STAMPING;
import static com.simex.twin.domain.control.sequence.MachineState.THERMAL_WAIT;
import static com.simex.twin.domain.control.sequence.MachineState.TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.io.ValveCommand;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tabla de transiciones de la sección 4.6 y los ciclos de cada estado. */
class SequenceTest {

    /** La tabla como datos: desde dónde, qué estímulo y a qué estado debe llegar en el siguiente scan. */
    static Stream<Arguments> transitionTable() {
        Consumer<ControlHarness> off = h -> {};
        Consumer<ControlHarness> heating = h -> h.command(new MachineCommand.Start());
        Consumer<ControlHarness> transport = ControlHarness::inTransport;
        Consumer<ControlHarness> thermalWait = h -> {
            h.inTransport();
            h.tempC = 45;
            h.photoelectric = true;
            h.scan();
        };
        return Stream.of(
                row("OFF + START", off, h -> h.command(new MachineCommand.Start()), HEATING),
                row("HEATING + STOP", heating, h -> h.command(new MachineCommand.Stop()), OFF),
                row("TRANSPORT + inductivo ↑", transport, h -> h.inductive = true, DISCARD),
                row("TRANSPORT + fotoeléctrico ↑ en ventana", transport, h -> h.photoelectric = true, STAMPING),
                row(
                        "TRANSPORT + fotoeléctrico ↑ fuera de ventana",
                        transport,
                        h -> {
                            h.tempC = 45;
                            h.photoelectric = true;
                        },
                        THERMAL_WAIT),
                row("TRANSPORT + STOP", transport, h -> h.command(new MachineCommand.Stop()), OFF),
                row("THERMAL_WAIT + STOP", thermalWait, h -> h.command(new MachineCommand.Stop()), OFF));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("transitionTable")
    void transitionTableHolds(
            String name, Consumer<ControlHarness> setup, Consumer<ControlHarness> stimulus, MachineState expected) {
        ControlHarness h = new ControlHarness();
        setup.accept(h);
        stimulus.accept(h);
        h.scan();

        assertThat(h.state()).isEqualTo(expected);
    }

    @Test
    void heatingWaitsForTwoStableSecondsInTheWindow() {
        ControlHarness h = new ControlHarness();
        h.command(new MachineCommand.Start());
        h.tempC = 60;
        h.scanFor(2_000); // 20 scans: el último está en t = 1,9 s desde el primero en ventana

        assertThat(h.state()).isEqualTo(HEATING);
        h.scan();
        assertThat(h.state()).isEqualTo(TRANSPORT);
        assertThat(h.scan().motorPwm().value()).isEqualTo(0.7);
    }

    @Test
    void heatupTimeoutWarnsOnceAndClearsWhenLeavingHeating() {
        ControlHarness h = new ControlHarness();
        h.command(new MachineCommand.Start());
        h.tempC = 30;
        h.scanFor(901_000);
        assertThat(alarmsRaised(h, AlarmCode.HEATUP_TIMEOUT)).isEqualTo(1);

        h.tempC = 62.5;
        h.scanFor(2_200);

        assertThat(h.state()).isEqualTo(TRANSPORT);
        assertThat(h.control.status().activeAlarms()).noneMatch(a -> a.code() == AlarmCode.HEATUP_TIMEOUT);
    }

    @Test
    void discardBrakesExtendsWhenStoppedAndRetracts() {
        ControlHarness h = new ControlHarness().inTransport();
        h.inductive = true;

        ProcessOutputs braking = h.scan(); // banda todavía en marcha
        assertThat(h.state()).isEqualTo(DISCARD);
        assertThat(braking.motorPwm().value()).isZero();
        assertThat(braking.discardValve()).isEqualTo(ValveCommand.DE_ENERGIZED);

        assertThat(h.scan().discardValve()).isEqualTo(ValveCommand.ENERGIZED); // detenida: extiende
        h.inductive = false; // el pistón retiró la pieza
        assertThat(h.scan().discardValve()).isEqualTo(ValveCommand.DE_ENERGIZED); // extendido: retrae
        h.scan(); // retraído
        assertThat(h.state()).isEqualTo(TRANSPORT);
    }

    @Test
    void stampingStopsTheBeltExtendsDwellsAndReportsTheCycle() {
        ControlHarness h = new ControlHarness().inTransport();
        h.photoelectric = true;

        assertThat(h.scan().stampValve()).isEqualTo(ValveCommand.DE_ENERGIZED); // frenando
        assertThat(h.state()).isEqualTo(STAMPING);
        assertThat(h.scan().stampValve()).isEqualTo(ValveCommand.ENERGIZED); // detenida: extiende
        h.scanFor(1_000); // llega al fin de carrera y mantiene la permanencia
        assertThat(h.outputs.stampValve()).isEqualTo(ValveCommand.ENERGIZED);
        h.scanFor(300); // retrae y vuelve a TRANSPORTE

        assertThat(h.state()).isEqualTo(TRANSPORT);
        ControlEvent.StampCompleted cycle =
                h.eventsOf(ControlEvent.StampCompleted.class).getFirst();
        assertThat(cycle.tempC()).isCloseTo(62.5, within(0.1));
        assertThat(cycle.waitMs()).isEqualTo(100); // un scan de frenado
        assertThat(cycle.strokeMs()).isEqualTo(100); // la planta ideal llega en un scan
        assertThat(h.control.status().lastStampStrokeMs()).hasValue(100);
    }

    @Test
    void thermalWaitAlarmsAfterTwoMinutesAndStampsOnceStable() {
        ControlHarness h = new ControlHarness().inTransport();
        h.tempC = 45;
        h.photoelectric = true;
        h.scan();
        assertThat(h.state()).isEqualTo(THERMAL_WAIT);

        h.scanFor(120_100);
        assertThat(alarmsRaised(h, AlarmCode.THERMAL_WAIT_LONG)).isEqualTo(1);

        h.tempC = 62.5;
        h.scanFor(2_100);
        assertThat(h.state()).isEqualTo(STAMPING);
        assertThat(h.control.status().activeAlarms()).noneMatch(a -> a.code() == AlarmCode.THERMAL_WAIT_LONG);
    }

    @Test
    void stopDuringACycleWaitsForThePistonToRetract() {
        ControlHarness h = new ControlHarness().inTransport();
        h.inductive = true;
        h.scan();
        h.command(new MachineCommand.Stop());
        h.scan();

        assertThat(h.state()).isEqualTo(DISCARD);
        h.inductive = false;
        h.scanFor(300);
        assertThat(h.state()).isEqualTo(OFF);
    }

    @Test
    void noTriggersWhileTheBeltIsStopped() {
        ControlHarness h = new ControlHarness();
        h.command(new MachineCommand.Start());
        h.inductive = true; // aparecen con la banda detenida en CALENTANDO
        h.photoelectric = true;
        h.tempC = 62.5;
        h.scanFor(3_000);

        assertThat(h.state()).isEqualTo(TRANSPORT);
    }

    /** Decisión 2 de la T6: dos flancos en el mismo scan se atienden los dos, primero el descarte. */
    @Test
    void simultaneousDetectionsAreBothServed() {
        ControlHarness h = new ControlHarness().inTransport();
        h.inductive = true;
        h.photoelectric = true;
        h.scan();
        assertThat(h.state()).isEqualTo(DISCARD);

        h.inductive = false;
        h.scanFor(300);
        assertThat(h.state()).isEqualTo(STAMPING);
    }

    private static Arguments row(
            String name, Consumer<ControlHarness> setup, Consumer<ControlHarness> stimulus, MachineState expected) {
        return Arguments.of(name, setup, stimulus, expected);
    }

    private static long alarmsRaised(ControlHarness h, AlarmCode code) {
        return h.eventsOf(ControlEvent.AlarmRaised.class).stream()
                .filter(e -> e.code() == code)
                .count();
    }
}
