package com.simex.twin.domain.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import org.junit.jupiter.api.Test;

class ControlLogicTest {

    @Test
    void tempLowUsesTheBandHysteresisAndOnlyWhileOperating() {
        ControlHarness h = new ControlHarness().inTransport();
        h.tempC = 49.9;
        h.scan();
        assertThat(alarmActive(h, AlarmCode.TEMP_LOW)).isTrue();

        h.tempC = 50.3; // dentro de la ventana, pero sin superar la histéresis de 0,5 °C
        h.scan();
        assertThat(alarmActive(h, AlarmCode.TEMP_LOW)).isTrue();
        assertThat(h.control.status().band()).isEqualTo(TemperatureBand.LOW);

        h.tempC = 50.6;
        h.scan();
        assertThat(alarmActive(h, AlarmCode.TEMP_LOW)).isFalse();
        assertThat(h.control.status().band()).isEqualTo(TemperatureBand.OK);
    }

    @Test
    void tempHighClearsWhenLeavingTheOperatingStates() {
        ControlHarness h = new ControlHarness().inTransport();
        h.tempC = 80;
        h.scan();
        assertThat(alarmActive(h, AlarmCode.TEMP_HIGH)).isTrue();

        h.command(new MachineCommand.Stop());
        h.scan();
        assertThat(alarmActive(h, AlarmCode.TEMP_HIGH)).isFalse();
    }

    @Test
    void switchingModesKeepsTheOutputAndLegacyRejectsPidSettings() {
        ControlHarness h = new ControlHarness().inTransport();
        assertThat(h.command(new MachineCommand.SetMode(ControlMode.LEGACY))).isInstanceOf(CommandResult.Applied.class);

        assertThat(h.command(new MachineCommand.SetSetpoint(63))).isInstanceOf(CommandResult.Rejected.class);
        assertThat(h.command(new MachineCommand.SetMode(ControlMode.PID))).isInstanceOf(CommandResult.Applied.class);
        assertThat(h.control.status().mode()).isEqualTo(ControlMode.PID);
    }

    /** Decisión 2 de la T5: LEGACY maneja el SSR directo, sin la ventana proporcional. */
    @Test
    void legacyDrivesTheSsrDirectly() {
        ControlHarness h = new ControlHarness().inTransport();
        h.command(new MachineCommand.SetMode(ControlMode.LEGACY));
        h.tempC = 45;
        h.scanFor(1_100);
        assertThat(h.outputs.ssr()).isTrue();
        assertThat(h.control.status().heaterDuty()).isEqualTo(1.0);

        h.tempC = 55;
        h.scanFor(1_100);
        assertThat(h.outputs.ssr()).isFalse();
        assertThat(h.control.status().heaterDuty()).isZero();
    }

    @Test
    void agentSettingsApplyAndRepeatedHeartbeatsChangeNothing() {
        ControlHarness h = new ControlHarness().inTransport();
        h.control.heartbeat(h.wallMs, h.events);
        h.control.heartbeat(h.wallMs + 1_000, h.events);

        assertThat(h.command(Issuer.AGENT, new MachineCommand.SetPid(9.5, 0.11, 0.0)))
                .isInstanceOf(CommandResult.Applied.class);
        assertThat(h.control.status().kp()).isEqualTo(9.5);
        assertThat(h.control.status().ki()).isEqualTo(0.11);
        assertThat(h.eventsOf(ControlEvent.SupervisorChanged.class)).hasSize(1);
    }

    @Test
    void theHeaterIsOffWhenTheMachineIsOff() {
        ControlHarness h = new ControlHarness();
        h.scanFor(5_000);

        assertThat(h.outputs.ssr()).isFalse();
        assertThat(h.control.status().heaterDuty()).isZero();
        assertThat(h.control.status().tcMillivolts()).isZero();
    }

    private static boolean alarmActive(ControlHarness h, AlarmCode code) {
        return h.control.status().activeAlarms().stream().anyMatch(a -> a.code() == code);
    }
}
