package com.simex.twin.domain.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.control.alarm.AlarmCode;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.safety.Supervisor;
import org.junit.jupiter.api.Test;

/** Anexo A: último latido en t₀ (pared) → parámetros seguros y alarma en t₀ + 10 s (±1 ciclo). */
class WatchdogTest {

    @Test
    void withoutHeartbeatsTheAgentNeverSupervised() {
        ControlHarness h = new ControlHarness().inTransport();
        h.scanFor(60_000);

        assertThat(h.control.status().supervisor()).isEqualTo(Supervisor.NONE);
        assertThat(h.control.status().agentHeartbeatAgeMs()).isEmpty();
        assertThat(h.control.status().activeAlarms()).noneMatch(a -> a.code() == AlarmCode.WATCHDOG_TIMEOUT);
    }

    @Test
    void aSilentAgentGetsSafeParametersOnceAndRecoversWithItsNextHeartbeat() {
        ControlHarness h = new ControlHarness().inTransport();
        h.control.heartbeat(h.wallMs, h.events);
        long t0 = h.wallMs;
        assertThat(h.control.status().supervisor()).isEqualTo(Supervisor.AGENT);
        h.command(Issuer.AGENT, new MachineCommand.SetSetpoint(65.0));
        h.command(Issuer.AGENT, new MachineCommand.SetBeltSpeed(0.8));

        while (h.wallMs <= t0 + 10_000) {
            h.scan();
        }
        assertThat(h.control.status().supervisor()).isEqualTo(Supervisor.AGENT); // aún no pasó de 10 s
        h.scan(); // t₀ + 10,1 s

        assertThat(h.control.status().supervisor()).isEqualTo(Supervisor.NONE);
        assertThat(h.control.status().setpointC()).isEqualTo(62.5);
        assertThat(h.control.status().beltPwm()).isEqualTo(0.7);
        h.scanFor(30_000);
        assertThat(h.eventsOf(ControlEvent.AlarmRaised.class))
                .filteredOn(e -> e.code() == AlarmCode.WATCHDOG_TIMEOUT)
                .hasSize(1);

        h.control.heartbeat(h.wallMs, h.events);

        assertThat(h.control.status().supervisor()).isEqualTo(Supervisor.AGENT);
        assertThat(h.control.status().activeAlarms()).noneMatch(a -> a.code() == AlarmCode.WATCHDOG_TIMEOUT);
        assertThat(h.control.status().setpointC()).isEqualTo(62.5); // los seguros quedan hasta nuevos ajustes
        assertThat(h.eventsOf(ControlEvent.SupervisorChanged.class))
                .extracting(ControlEvent.SupervisorChanged::to)
                .containsExactly(Supervisor.AGENT, Supervisor.NONE, Supervisor.AGENT);
    }
}
