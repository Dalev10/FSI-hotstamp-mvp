package com.simex.twin.domain.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.command.Rule;
import com.simex.twin.domain.control.sequence.MachineState;
import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.ProcessOutputs;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Interlocks, salidas seguras y RESET (secciones 4.7 y 9.2). */
class SafetyTest {

    private static final MachineCommand.AckAlarm ACK_ALL = new MachineCommand.AckAlarm(Optional.empty());

    @Test
    void emergencyStopForcesSafeOutputsInTheSameScan() {
        ControlHarness h = new ControlHarness().inTransport();
        h.command(new MachineCommand.EmergencyStop());

        assertThat(h.scan()).isEqualTo(ProcessOutputs.SAFE);
        assertThat(h.state()).isEqualTo(MachineState.EMERGENCY_STOP);
        assertThat(h.control.status().estop()).isTrue();
        assertThat(h.control.status().interlock()).hasValue(AlarmCode.EMERGENCY_STOP);
    }

    @Test
    void resetNeedsTheAlarmsAcknowledgedFirst() {
        ControlHarness h = new ControlHarness().inTransport();
        h.command(new MachineCommand.EmergencyStop());
        h.scan();

        assertThat(rejection(h.command(new MachineCommand.Reset()))).isEqualTo(Rule.ALARMS_NOT_ACKNOWLEDGED);
        h.command(ACK_ALL);
        assertThat(h.command(new MachineCommand.Reset())).isInstanceOf(CommandResult.Applied.class);
        h.scan();

        assertThat(h.state()).isEqualTo(MachineState.OFF);
        assertThat(h.control.status().activeAlarms()).isEmpty();
        assertThat(h.control.status().interlock()).isEmpty();
    }

    @Test
    void anOpenThermocoupleWinsOverTheOverTemperatureItAlsoCauses() {
        ControlHarness h = new ControlHarness().inTransport();
        h.forcedCounts = 4095; // T_meas ≈ 347 °C: también supera los 90 °C

        assertThat(h.scan()).isEqualTo(ProcessOutputs.SAFE);
        assertThat(h.state()).isEqualTo(MachineState.FAULT);
        assertThat(h.control.status().activeAlarms())
                .extracting(a -> a.code())
                .containsExactly(AlarmCode.THERMOCOUPLE_OPEN);
    }

    @Test
    void resetIsRejectedWhileTheCauseIsActive() {
        ControlHarness h = new ControlHarness().inTransport();
        h.tempC = 95;
        h.scan();
        assertThat(h.state()).isEqualTo(MachineState.FAULT);
        h.command(ACK_ALL);

        assertThat(rejection(h.command(new MachineCommand.Reset()))).isEqualTo(Rule.CAUSE_ACTIVE);
        h.tempC = 60;
        h.scan();
        assertThat(h.command(new MachineCommand.Reset())).isInstanceOf(CommandResult.Applied.class);
    }

    @Test
    void aCylinderThatDoesNotReachItsEndTripsAtTheStrokeTimeout() {
        ControlHarness h = new ControlHarness().inTransport();
        h.idealPlant = false;
        h.photoelectric = true;
        h.scan(); // ESTAMPADO, frenando
        h.speedMps = 0;
        h.scan(); // detenida: se energiza la válvula del troquel
        h.stamp = CylinderPosition.EXTENDING; // fuga ×5: no llega a tiempo
        h.scanFor(1_400);
        assertThat(h.state()).isEqualTo(MachineState.STAMPING);

        h.scan(); // 1500 ms desde el mando
        assertThat(h.state()).isEqualTo(MachineState.FAULT);
        assertThat(h.control.status().activeAlarms().getFirst().detail()).hasValue("STAMP");

        h.command(ACK_ALL);
        h.stamp = CylinderPosition.RETRACTING;
        h.scan();
        assertThat(rejection(h.command(new MachineCommand.Reset()))).isEqualTo(Rule.CAUSE_ACTIVE);
        h.stamp = CylinderPosition.RETRACTED;
        h.scan();
        assertThat(h.command(new MachineCommand.Reset())).isInstanceOf(CommandResult.Applied.class);
    }

    @Test
    void aStuckDiscardCylinderIsReportedAsSuch() {
        ControlHarness h = new ControlHarness().inTransport();
        h.idealPlant = false;
        h.inductive = true;
        h.scan(); // DESCARTE, frenando
        h.speedMps = 0;
        h.scan(); // detenida: se energiza la válvula del pistón
        h.discard = CylinderPosition.EXTENDING;
        h.scanFor(1_500);

        assertThat(h.state()).isEqualTo(MachineState.FAULT);
        assertThat(h.control.status().activeAlarms().getFirst().detail()).hasValue("DISCARD");
    }

    @Test
    void interlocksAlsoWatchAStoppedMachine() {
        ControlHarness h = new ControlHarness();
        h.forcedCounts = 4095;
        h.scan();

        assertThat(h.state()).isEqualTo(MachineState.FAULT);
    }

    @Test
    void anEmergencyStopDuringAFaultTakesOver() {
        ControlHarness h = new ControlHarness();
        h.tempC = 95;
        h.scan();
        h.command(new MachineCommand.EmergencyStop());
        h.scan();

        assertThat(h.state()).isEqualTo(MachineState.EMERGENCY_STOP);
        assertThat(h.control.status().activeAlarms())
                .extracting(a -> a.code())
                .containsExactly(AlarmCode.OVER_TEMPERATURE, AlarmCode.EMERGENCY_STOP);
    }

    @Test
    void settingsAreRejectedWhileHalted() {
        ControlHarness h = new ControlHarness();
        h.command(new MachineCommand.EmergencyStop());
        h.scan();

        assertThat(rejection(h.command(new MachineCommand.SetBeltSpeed(0.8)))).isEqualTo(Rule.STATE_NOT_ALLOWED);
    }

    private static Rule rejection(CommandResult result) {
        assertThat(result).isInstanceOf(CommandResult.Rejected.class);
        return ((CommandResult.Rejected) result).rule();
    }
}
