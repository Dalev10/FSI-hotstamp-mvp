"""Prueba de regresión del ciclo de ajuste con los mensajes del contrato."""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

import httpx

from simex.agent import Agent
from simex.policy import gains, noise_adjustment
from simex.results import Journal

EXAMPLES = Path(__file__).resolve().parents[1] / "contracts" / "examples"


def example(name):
    return json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))


class SupervisionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name)
        self.agent = Agent(AsyncMock(), Journal(path, path / "ref.csv"))
        self.agent.limits = example("rest/Limits")
        state = example("hello")
        state["seq"] = 0
        self.agent.receive(state)
        self.run = self.agent.run
        self.run.config = {"mode": "auto", "scenario": "ruido-sensor"}
        self.seq = 0
        self.snapshot = state["snapshot"]
        self.original = gains(self.snapshot)

        async def accept(route, *, json):
            return httpx.Response(202, json={"commandId": json["commandId"], "status": "APPLIED"})

        self.agent.twin.http.post.side_effect = accept

    def emit(self, kind, sim_s, **data):
        self.seq += 1
        self.agent.receive(dict(data, type=kind, runId=self.run.id, seq=self.seq, simTimeMs=sim_s * 1000))

    def temperatures(self, first, count, noise=0.3, duty_variation=0.09, error=0):
        for second in range(first, first + count):
            sign = (-1) ** second
            thermal = dict(self.snapshot["thermal"], tempC=62.5 + error + sign * noise,
                           heaterDuty=0.3 + sign * duty_variation)
            data = dict(self.snapshot, thermal=thermal)
            self.emit("telemetry", second, **data)

    def confirmation(self, action, sim_s):
        self.emit("event", sim_s, eventType="COMMAND_APPLIED", data={
            "commandId": action.command_id, "issuedBy": "agent", "type": "SET_PID",
        })

    async def propose(self):
        self.temperatures(1, 120)
        self.temperatures(121, 120, noise=1.5)
        if self.agent.command_task:
            await self.agent.command_task

    async def test_shadow_proposes_without_a_command(self):
        self.run.config["mode"] = "shadow"
        await self.propose()
        self.assertEqual(self.run.history[-1]["result"], "shadow")
        self.assertEqual(gains(self.run.snapshot), self.original)
        self.agent.twin.http.post.assert_not_called()

    async def test_202_requires_matching_event_and_telemetry_then_verifies(self):
        await self.propose()
        action = self.run.intervention
        self.snapshot["control"].update(action.target)
        self.temperatures(241, 1, noise=1.5)
        self.assertIsNone(action.confirmed_ms)
        self.confirmation(action, 241)
        self.temperatures(242, 180, noise=1.5, duty_variation=0.05)
        self.assertEqual(self.run.history[-1]["result"], "verified")
        self.assertEqual(self.run.adjustments, 1)
        self.assertEqual(self.run.history[-1]["response"]["status"], 202)

    async def test_deterioration_restores_gains_through_another_confirmed_command(self):
        await self.propose()
        action = self.run.intervention
        self.snapshot["control"].update(action.target)
        self.confirmation(action, 240)
        self.temperatures(241, 180, noise=1.5, error=3)
        await self.agent.command_task
        rollback = self.run.intervention
        self.assertTrue(rollback.rollback)
        self.assertNotEqual(rollback.command_id, action.command_id)
        self.assertEqual(rollback.target, self.original)
        self.snapshot["control"].update(self.original)
        self.confirmation(rollback, 421)
        self.temperatures(421, 1, noise=1.5)
        self.assertEqual(self.run.history[-1]["result"], "rolled_back")
        self.assertEqual(self.agent.twin.http.post.await_count, 2)

    async def test_timeout_reconciles_with_public_event_and_gains(self):
        self.agent.twin.http.post.side_effect = httpx.ReadTimeout("Se perdió la respuesta HTTP")
        await self.propose()
        action = self.run.intervention
        self.assertEqual(action.response, "uncertain")
        self.confirmation(action, 240)
        self.snapshot["control"].update(action.target)
        self.temperatures(241, 1, noise=1.5)
        self.assertEqual(self.run.history[-1]["result"], "applied")
        self.assertIsNone(self.run.history[-1]["response"]["status"])
        self.agent.twin.http.post.assert_awaited_once()

    async def test_stream_gap_breaks_the_reference(self):
        self.temperatures(1, 120)
        self.assertIsNotNone(self.run.reference)
        self.seq += 1
        self.temperatures(122, 120, noise=1.5)
        self.assertIsNone(self.run.reference)
        self.assertGreater(self.run.gaps, 0)
        self.agent.twin.http.post.assert_not_called()

    async def test_critical_alarm_cancels_a_scheduled_send(self):
        self.temperatures(1, 120)
        self.temperatures(121, 120, noise=1.5)
        cause = "THERMOCOUPLE_OPEN"
        alarm = {"code": cause, "severity": "CRITICAL", "raisedAtSimMs": 240000}
        self.emit("event", 240, eventType="ALARM_RAISED", data=alarm)
        await self.agent.command_task
        tripped = copy.deepcopy(self.snapshot)
        tripped["state"] = "FAULT"
        tripped["safety"].update(interlock=cause, activeAlarms=[alarm])
        tripped["thermal"].update(heaterDuty=0, ssr=False)
        tripped["conveyor"].update(running=False, speedMps=0)
        tripped["valves"].update(stamp="RETRACTED", discard="RETRACTED")
        self.emit("telemetry", 241, **tripped)
        self.assertEqual(self.run.history[-1]["result"], "safe_stop")
        self.agent.twin.http.post.assert_not_called()

    async def test_adjustment_is_conservative_with_published_limits(self):
        target = noise_adjustment(self.original, self.agent.limits)
        self.assertEqual(target, {"kp": 8.35, "ki": 0.0835, "kd": 0.0})

    async def test_watchdog_records_restored_parameters(self):
        self.run.initial = dict(self.original, setpointC=62.5, beltPwm=0.7)
        self.snapshot["control"].update(kp=8.35, ki=0.0835)
        self.temperatures(1, 1)
        self.emit("event", 2, eventType="SUPERVISOR_CHANGED", data={"from": "AGENT", "to": "NONE"})
        self.snapshot["control"].update(self.original)
        self.temperatures(2, 1)
        result = self.run.history[-1]
        self.assertEqual(result["result"], "observed")
        self.assertEqual(result["evidence"]["before"]["kp"], 8.35)
        self.assertEqual(result["evidence"]["restored"]["kp"], 10)
