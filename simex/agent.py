"""Observa el flujo público, decide, envía un ajuste y verifica su efecto."""

import asyncio
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from math import isclose
from statistics import fmean, pstdev
from uuid import uuid4

import httpx
from websockets.exceptions import WebSocketException

from . import policy
from .results import LATE_WINDOW_S

LOG = logging.getLogger(__name__)
FRESH_S = 3.5  # Reloj real. La frecuencia normal más baja es una muestra por segundo.
HEARTBEAT_S = 2
CONFIRM_S = 3  # Plazo real después de la respuesta REST para reconciliar la aplicación.
DEMO_SCENARIOS = ("nominal", "ruido-sensor", "frente-frio", "termocupla-abierta")
SCENARIO_DIAGNOSIS = {"ruido-sensor": "noise", "frente-frio": "cold", "termocupla-abierta": "critical"}


def same_gains(left, right):
    return all(isclose(left[name], right[name], abs_tol=1e-8) for name in policy.GAINS)


@dataclass
class Intervention:
    decision: dict
    original: dict
    target: dict
    before: dict
    command_id: str = field(default_factory=lambda: str(uuid4()))
    rollback: bool = False
    response: str = "pending"
    event_ms: int = None
    confirmed_ms: int = None
    deadline: float = 0
    samples: list = field(default_factory=list)


class Run:
    def __init__(self, run_id):
        self.id = run_id
        self.config = None  # Solo se asigna a corridas iniciadas por esta aplicación.
        self.done = asyncio.Event()
        self.snapshot = None
        self.seq = None
        self.last_ms = None
        self.gaps = 0
        self.samples = 0
        self.operating = 0
        self.in_band = 0
        self.late_duty = []
        self.late_errors = []
        self.piece_wait_ms = 0
        self.piece_events = 0
        self.graph = deque(maxlen=3601)
        self.marks = deque(maxlen=100)
        self.history = deque(maxlen=20)
        self.window = []
        self.baselines = []
        self.reference = None
        self.noise_windows = deque(maxlen=policy.PERSIST_WINDOWS)
        self.cold_windows = 0
        self.last_cold_response = None
        self.noise_handled = False
        self.critical = False
        self.watchdog_tripped = False
        self.watchdog_event = None
        self.initial = None
        self.intervention = None
        self.adjustments = 0
        self.decision_at = None
        self.applied_at = None
        self.delay = None
        self.result = None
        self.end_reason = None

    def reset_windows(self, include_reference=False):
        self.window.clear()
        self.noise_windows.clear()
        self.cold_windows = 0
        if include_reference:
            self.baselines.clear()
            self.reference = None

    def summary(self, end_ms):
        duration = end_ms / 1000
        snapshot = self.snapshot or {}
        counters = snapshot.get("counters", {})
        energy = snapshot.get("energy")
        stamped = counters.get("plasticStamped")
        missing = max(0, int(duration) - self.samples)
        watchdog_changed_control = self.watchdog_tripped and not self.critical
        complete = self.end_reason == "COMPLETED" and missing == 0 and self.gaps == 0 and not watchdog_changed_control
        return {
            "experiment": self.config["experiment"], "arm": self.config["arm"],
            "runId": self.id, "scenario": self.config["scenario"], "seed": self.config["seed"],
            "speed": self.config["speed"], "durationS": duration,
            "status": "complete" if complete else "inconclusive",
            "samples": self.samples, "samplesMissing": missing, "streamGaps": self.gaps,
            "operatingSamples": self.operating, "stamped": stamped,
            "piecesPerHour": round(stamped * 3600 / duration, 2) if duration and stamped is not None else None,
            "inBandPct": round(100 * self.in_band / self.operating, 3) if self.operating else None,
            "kwh": round((energy["heaterWh"] + energy["motorWh"]) / 1000, 6) if energy else None,
            "waitMeanS": round(self.piece_wait_ms / self.piece_events / 1000, 3) if self.piece_events else None,
            "metalEscaped": counters.get("metalEscaped"),
            "lateErrorC": round(fmean(self.late_errors), 4) if self.late_errors else None,
            "lateDutySdPp": round(pstdev(self.late_duty) * 100, 4) if len(self.late_duty) > 1 else None,
            "decisionAtS": self.decision_at,
            "appliedAtS": self.applied_at, "detectionDelayS": self.delay,
            "adjustments": self.adjustments, "result": self.result or "no_adjustment",
        }


class Agent:
    def __init__(self, twin, journal):
        self.twin = twin
        self.journal = journal
        self.mode = "shadow"
        self.run = Run(None)
        self.limits = None
        self.scenarios = []
        self.connected = False
        self.last_sample = None
        self.starting = False
        self.run_changed = asyncio.Event()
        self.command_task = None
        self.experiment_task = None
        self.experiment = None
        self.heartbeat_seq = 0
        self.heartbeat_time = 0
        self.last_error = None

    def fresh(self):
        return self.connected and self.last_sample is not None and time.monotonic() - self.last_sample <= FRESH_S

    async def listen(self):
        while True:
            try:
                self.limits = await self.twin.read("limits")
                self.scenarios = await self.twin.read("sim/scenarios")
                state = await self.twin.read("state")
                if self.run.id is None and self.run.snapshot is None:
                    self.run.snapshot = state
                async for message in self.twin.messages():
                    self.receive(message)
                self.last_error = "El gemelo cerró la conexión de telemetría."
            except (httpx.HTTPError, WebSocketException, OSError, TimeoutError) as error:
                LOG.info("Conexión con el gemelo: %s", error)
                self.last_error = "No se pudo mantener la conexión con el gemelo. Reintentando."
            self.connected = False
            self.interrupt("Se interrumpió el flujo de telemetría.")
            await asyncio.sleep(1)

    def interrupt(self, reason, sequence_gap=False):
        run = self.run
        had_sample = self.last_sample is not None
        self.last_sample = None
        if not run.done.is_set() and (run.last_ms is not None or run.config):
            # Una vez perdida la referencia previa a la falla, la corrida conserva solo observación.
            if had_sample or sequence_gap or run.gaps == 0:
                run.gaps += 1
            run.reset_windows(include_reference=True)
            self.cancel_intervention(reason)

    def receive(self, message):
        run_id = message["runId"]
        if run_id != self.run.id:
            if not self.run.done.is_set():
                self.finish(self.run, "UNKNOWN", self.run.last_ms or 0)
            self.run = Run(run_id)
            self.last_sample = None
            self.run_changed.set()
        run = self.run
        self.connected = True
        self.last_error = None
        if message["type"] == "hello":
            run.seq = message["seq"]
            run.snapshot = message["snapshot"]
            return
        if run.done.is_set():
            return
        if run.seq is not None and message["seq"] != run.seq + 1:
            self.interrupt("Se detectó un hueco en la secuencia de mensajes.", sequence_gap=True)
        run.seq = message["seq"]
        if message["type"] == "event":
            self.event(run, message)
        elif message["type"] == "telemetry":
            self.observe(run, message)

    def event(self, run, event):
        event_type = event["eventType"]
        data = event["data"]
        sim_ms = event["simTimeMs"]
        if event_type == "RUN_STARTED":
            run.initial = data["parameters"]
        elif event_type == "PIECE_STAMPED":
            run.piece_events += 1
            run.piece_wait_ms += data["waitMs"]
        elif event_type == "ALARM_RAISED":
            run.marks.append((sim_ms, "alarm", data["code"]))
            if data["severity"] == "CRITICAL":
                run.critical = True
                self.cancel_intervention("El gemelo activó una alarma crítica.", sim_ms)
        elif event_type == "COMMAND_APPLIED" and data["issuedBy"] == "agent":
            run.marks.append((sim_ms, "adjustment", data["type"]))
            action = run.intervention
            if action and data["commandId"] == action.command_id and data["type"] == "SET_PID":
                action.event_ms = sim_ms
        elif event_type == "SUPERVISOR_CHANGED" and data["to"] == "NONE" and run.config:
            run.watchdog_tripped = True
            run.watchdog_event = {"eventAtMs": sim_ms, "supervisor": data, "before": self.parameters(run.snapshot)}
            self.cancel_intervention("El vigilante del gemelo detectó la pérdida del latido.", sim_ms)
        elif event_type == "RUN_ENDED":
            self.finish(run, data["reason"], sim_ms)

    def observe(self, run, snapshot):
        sim_ms = snapshot["simTimeMs"]
        if run.last_ms is not None and sim_ms != run.last_ms + 1000 and self.last_sample is not None:
            self.interrupt("Faltó una muestra de temperatura.")
        self.last_sample = time.monotonic()
        run.snapshot = snapshot
        run.last_ms = sim_ms
        run.samples += 1
        thermal = snapshot["thermal"]
        run.graph.append((sim_ms, thermal["tempC"], thermal["setpointC"]))
        if policy.blocked(snapshot):
            run.watchdog_event = None
            self.observe_critical(run, snapshot)
            return
        if run.watchdog_event:
            self.observe_watchdog(run)
        run.critical = False
        if snapshot["state"] not in policy.OPERATING:
            run.reset_windows()
            self.cancel_intervention("La máquina salió de operación.")
            return
        low, high = (self.limits["process"][key] for key in ("windowMinC", "windowMaxC"))
        run.operating += 1
        run.in_band += low <= thermal["tempC"] <= high
        if sim_ms >= LATE_WINDOW_S * 1000:
            run.late_duty.append(thermal["heaterDuty"])
            run.late_errors.append(abs(thermal["tempC"] - thermal["setpointC"]))
        if not run.config or run.config["mode"] == "off":
            return
        if run.intervention:
            self.check_intervention(run)
        if run.gaps or run.watchdog_tripped or run.intervention:
            return
        run.window.append(thermal)
        if len(run.window) == policy.WINDOW_S:
            samples, run.window = run.window, []
            self.diagnose(run, samples)

    @staticmethod
    def parameters(snapshot):
        if not snapshot:
            return None
        return dict(policy.gains(snapshot), setpointC=snapshot["thermal"]["setpointC"],
                    beltPwm=snapshot["conveyor"]["pwmDuty"])

    def observe_watchdog(self, run):
        restored = self.parameters(run.snapshot)
        matches = run.initial and all(isclose(value, run.initial[key]) for key, value in restored.items())
        self.record({
            "diagnosis": "watchdog", "title": "Latido de supervisión vencido",
            "explanation": "El gemelo activó el vigilante y dejó de depender del agente.",
            "evidence": dict(run.watchdog_event, restored=restored, reference=run.initial),
            "result": "observed" if matches else "inconclusive",
            "note": ("La telemetría confirmó la restauración de las ganancias, la consigna y la velocidad solicitada."
                     if matches else "La telemetría no permitió comprobar todos los valores restaurados."),
        }, run.last_ms)
        run.watchdog_event = None

    def observe_critical(self, run, snapshot):
        run.critical = True
        run.reset_windows(include_reference=True)
        self.cancel_intervention("La protección del gemelo interrumpió el ajuste.")
        safe = policy.safe_outputs(snapshot)
        if run.history and run.history[-1]["diagnosis"] == "critical":
            if run.history[-1]["evidence"]["safeOutputs"] == safe:
                return
        safety = snapshot["safety"]
        alarm = next((a for a in safety["activeAlarms"] if a["severity"] == "CRITICAL"), {})
        source = safety["interlock"] or alarm.get("code") or snapshot["state"]
        self.record({
            "diagnosis": "critical", "title": "Paro por protección del gemelo",
            "explanation": (
                "El gemelo ordenó el paro. La resistencia y la banda están apagadas y ambos pistones se retrajeron."
                if safe else "El gemelo activó una protección. Se está comprobando el estado de las salidas."
            ),
            "evidence": {
                "cause": source, "alarm": alarm, "state": snapshot["state"], "safeOutputs": safe,
                "heaterDuty": snapshot["thermal"]["heaterDuty"], "heaterOn": snapshot["thermal"]["ssr"],
                "beltRunning": snapshot["conveyor"]["running"], "beltSpeedMps": snapshot["conveyor"]["speedMps"],
                "discardValve": snapshot["valves"]["discard"], "stampValve": snapshot["valves"]["stamp"],
                "adjustments": run.adjustments,
            },
            "result": "safe_stop" if safe else "observed",
        }, run.last_ms)

    def diagnose(self, run, samples):
        values = policy.metrics(samples, self.limits)
        if not run.reference:
            if not policy.stable(samples, values):
                run.baselines.clear()
                return
            run.baselines.extend(samples)
            if len(run.baselines) >= policy.PERSIST_WINDOWS * policy.WINDOW_S:
                run.reference = policy.metrics(run.baselines, self.limits)
                self.record({
                    "diagnosis": "nominal", "title": "Referencia térmica estable",
                    "explanation": "Se completaron dos ventanas estables. El agente sigue observando.",
                    "evidence": {"observed": run.reference}, "result": "observed",
                }, run.last_ms)
            return
        reference = run.reference
        cold = reference["ambientC"] - values["ambientC"] >= policy.COLD_DROP_C
        run.cold_windows = run.cold_windows + 1 if cold else 0
        noisy = values["noiseC"] >= policy.NOISE_FACTOR * max(reference["noiseC"], policy.NOISE_FLOOR_C)
        if noisy:
            run.noise_windows.append(samples)
        else:
            run.noise_windows.clear()
        if run.cold_windows >= policy.PERSIST_WINDOWS:
            held = policy.cold_response(values)
            if run.last_cold_response != held:
                run.last_cold_response = held
                self.record({
                    "diagnosis": "cold", "title": "Frente frío · conservar el ajuste",
                    "explanation": (
                        "Bajó la temperatura ambiente y el regulador sostiene la consigna con margen de calefacción."
                        if held else "Bajó el ambiente y el error o la demanda exceden el margen. Se conserva el ajuste y se vigila su evolución."
                    ),
                    "evidence": {"observed": values, "reference": reference},
                    "result": "kept" if held else "monitoring",
                }, run.last_ms)
        elif not cold and len(run.noise_windows) == policy.PERSIST_WINDOWS and not run.noise_handled:
            self.decide_noise(run)

    def decide_noise(self, run):
        if run.snapshot["control"]["mode"] != "PID":
            return
        before = policy.metrics([s for window in run.noise_windows for s in window], self.limits)
        current = policy.gains(run.snapshot)
        target = policy.noise_adjustment(current, self.limits)
        decision = {
            "diagnosis": "noise", "title": "Ruido persistente en la termocupla",
            "explanation": "Dos ventanas superaron tres veces el ruido de referencia. El agente propone reducir las ganancias proporcional e integral para suavizar la demanda.",
            "evidence": {"observed": before, "reference": run.reference},
            "before": current, "proposed": target,
        }
        if not policy.allowed_step(current, target, self.limits):
            run.noise_handled = True
            self.record(dict(decision, result="rejected", note="El ajuste excede los límites o el paso publicado."), run.last_ms)
        elif run.config["mode"] == "shadow":
            run.noise_handled = True
            self.record(dict(decision, result="shadow", note="Propuesta registrada en modo sombra."), run.last_ms)
        elif self.can_write(run):
            run.noise_handled = True
            action = Intervention(decision, current, target, before)
            self.dispatch(run, action)

    def can_write(self, run):
        return (
            run is self.run and not self.starting and not run.done.is_set()
            and self.fresh() and not run.gaps and not run.critical and not run.watchdog_tripped
            and run.config["mode"] == "auto" and run.snapshot["state"] in policy.OPERATING
            and run.snapshot["control"]["mode"] == "PID" and run.snapshot["control"]["supervisor"] == "AGENT"
            and not policy.blocked(run.snapshot)
            and (
                self.command_task is None or self.command_task.done()
                or self.command_task is asyncio.current_task()
            )
        )

    def dispatch(self, run, action):
        run.intervention = action
        action.decision = self.record(dict(
            action.decision, commandId=action.command_id, result="sending", rollback=action.rollback,
        ), run.last_ms)
        self.command_task = asyncio.create_task(self.send(run, action))

    async def send(self, run, action):
        if run.intervention is not action:
            return
        if not self.can_write(run) or not same_gains(policy.gains(run.snapshot), action.decision["before"]):
            self.cancel_intervention("Cambió el contexto antes de enviar el comando.")
            return
        try:
            response = await self.twin.http.post("commands", json={
                "commandId": action.command_id, "type": "SET_PID", "issuedBy": "agent",
                "params": action.target, "reason": action.decision["explanation"],
            })
            body = response.json()
            reply = {"status": response.status_code, "body": body}
            if response.status_code == 202 and body.get("commandId") == action.command_id:
                action.response = "accepted"
            elif response.status_code in {400, 401, 403, 409}:
                action.response = "rejected"
            else:
                action.response = "uncertain"
        except (httpx.HTTPError, ValueError) as error:
            action.response = "uncertain"
            reply = {"status": None, "detail": str(error)}
        action.deadline = time.monotonic() + CONFIRM_S
        evidence = dict(action.decision, response=reply)
        if run is not self.run or run.intervention is not action:
            self.journal.log(dict(evidence, result="response_after_interruption"))
            return
        if action.response == "rejected":
            rule = reply["body"].get("rule") or reply["body"].get("detail") or reply["status"]
            self.record(dict(evidence, result="inconclusive" if action.rollback else "rejected",
                             note=f"El gemelo rechazó el comando ({rule})."), run.last_ms)
            run.intervention = None
            if reply["status"] == 409:
                try:
                    self.limits = await self.twin.read("limits")
                except httpx.HTTPError:
                    LOG.info("No se pudieron actualizar los límites tras el rechazo.")
            return
        action.decision = self.record(dict(evidence, result="confirming"), run.last_ms)

    def check_intervention(self, run):
        action = run.intervention
        now = run.last_ms
        current = policy.gains(run.snapshot)
        if action.confirmed_ms is None:
            if (
                action.response in {"accepted", "uncertain"} and action.event_ms is not None
                and now >= action.event_ms and same_gains(current, action.target)
            ):
                action.confirmed_ms = action.event_ms
                run.adjustments += 1
                evidence = dict(action.decision, confirmedGains=current, appliedAtMs=action.event_ms)
                if action.rollback:
                    self.record(dict(evidence, result="rolled_back", note="El gemelo confirmó la restauración."), now)
                    run.intervention = None
                else:
                    run.applied_at = action.event_ms / 1000
                    action.decision = self.record(dict(evidence, result="applied"), now)
            return
        if not same_gains(current, action.target):
            self.cancel_intervention("Las ganancias cambiaron durante la verificación.")
            return
        if now <= action.confirmed_ms + policy.SETTLE_S * 1000:
            return
        action.samples.append(run.snapshot["thermal"])
        if len(action.samples) < policy.PERSIST_WINDOWS * policy.WINDOW_S:
            return
        after = policy.metrics(action.samples, self.limits)
        result, note = policy.verify(action.before, after)
        decision = dict(action.decision, after=after, note=note)
        run.intervention = None
        if result == "rollback":
            if self.can_write(run) and policy.allowed_step(current, action.original, self.limits):
                restore = {
                    key: decision[key] for key in ("diagnosis", "title", "explanation", "evidence", "after", "note")
                }
                restore.update(previousCommandId=action.command_id, before=current, proposed=action.original)
                self.dispatch(run, Intervention(restore, action.original, action.original, after, rollback=True))
                return
            result = "inconclusive"
            decision["note"] += " La restauración quedó bloqueada por las guardas."
        self.record(dict(decision, result=result), now)

    def cancel_intervention(self, reason, sim_ms=None):
        run = self.run
        if run.intervention:
            sim_ms = run.last_ms if sim_ms is None else sim_ms
            self.record(dict(run.intervention.decision, result="inconclusive", note=reason), sim_ms or 0)
            run.intervention = None

    def record(self, decision, sim_ms):
        run = self.run
        mode = run.config["mode"] if run.config else "shadow"
        record = dict(decision, runId=run.id, simTimeMs=sim_ms, mode=mode, source="template")
        if run.config:
            record.update({key: run.config[key] for key in ("scenario", "seed", "speed") if key in run.config})
        diagnosis = record["diagnosis"]
        if diagnosis in {"cold", "noise", "critical"} and run.decision_at is None:
            run.decision_at = sim_ms / 1000
            # El tiempo de inyección solo etiqueta la evaluación, nunca entra a la regla.
            if (run.config and SCENARIO_DIAGNOSIS.get(run.config["scenario"]) == diagnosis
                    and run.decision_at >= 600):
                run.delay = round(run.decision_at - 600, 3)
        if diagnosis not in {"nominal", "watchdog"}:
            run.result = record["result"]
        run.history.append(record)
        self.journal.log(record)
        return record

    async def supervise(self):
        while True:
            await asyncio.sleep(0.2)
            run = self.run
            if self.last_sample is not None and not self.fresh():
                self.interrupt("La telemetría dejó de llegar durante la verificación.")
            action = run.intervention
            if action and action.confirmed_ms is None and action.deadline and time.monotonic() > action.deadline:
                self.cancel_intervention("No se pudo conciliar la respuesta con el evento y las ganancias medidas.")
            if (
                self.starting or not self.fresh() or not run.config or run.config["mode"] != "auto"
                or run.done.is_set() or run.gaps or run.critical or run.watchdog_tripped
                or run.snapshot["control"]["mode"] != "PID" or time.monotonic() - self.heartbeat_time < HEARTBEAT_S
            ):
                continue
            self.heartbeat_seq += 1
            self.heartbeat_time = time.monotonic()
            try:
                response = await self.twin.http.post("commands/heartbeat", json={
                    "agentId": "simex-local", "seq": self.heartbeat_seq,
                    "sentAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                })
                response.raise_for_status()
            except httpx.HTTPError as error:
                LOG.warning("No llegó el latido al gemelo: %s", error)

    async def launch(self, scenario, seed, speed, mode, arm="PID", experiment=""):
        if self.starting or (self.command_task and not self.command_task.done()):
            raise ValueError("Hay una solicitud pendiente. Espera su confirmación.")
        action = self.run.intervention
        if action and action.confirmed_ms is None:
            raise ValueError("Hay un comando pendiente de confirmar en el gemelo.")
        catalog = {entry["id"]: entry for entry in self.scenarios}
        if scenario not in DEMO_SCENARIOS or scenario not in catalog:
            raise ValueError("El escenario seleccionado no está disponible en el gemelo.")
        if not self.connected:
            raise ValueError("El tablero todavía no está conectado al gemelo.")
        self.starting = True
        try:
            run_id = await self.twin.start(scenario, seed, speed, "LEGACY" if arm == "LEGACY" else "PID")
            await asyncio.wait_for(self.wait_for_run(run_id), CONFIRM_S)
            self.run.config = {
                "scenario": scenario, "seed": seed, "speed": speed,
                "mode": mode, "arm": arm, "experiment": experiment,
                "duration": catalog[scenario]["durationSimS"],
            }
            self.heartbeat_time = 0
            return self.run
        finally:
            self.starting = False

    async def wait_for_run(self, run_id):
        while self.run.id != run_id:
            self.run_changed.clear()
            await self.run_changed.wait()

    def change_mode(self, mode):
        self.mode = mode
        run = self.run
        if run.config and not run.done.is_set() and run.config["mode"] != mode:
            run.config["mode"] = mode
            run.config["arm"] = "MIXED"
            if mode == "shadow":
                self.cancel_intervention("El operador pasó a modo sombra y suspendió el latido.")
            elif run.history and run.history[-1]["result"] == "shadow":
                run.noise_handled = False

    def finish(self, run, reason, sim_ms):
        if run.done.is_set():
            return
        self.cancel_intervention("La corrida terminó antes de completar la comprobación.", sim_ms)
        run.end_reason = reason
        if run.config:
            self.journal.save(run.summary(sim_ms))
        run.done.set()

    async def compare(self, scenario, seed, speed):
        experiment_id = str(uuid4())
        self.experiment = {"id": experiment_id, "status": "running", "arm": None, "scenario": scenario}
        comparable = True
        try:
            for arm, mode in (("LEGACY", "off"), ("PID", "off"), ("AGENT", "auto")):
                self.experiment["arm"] = arm
                run = await self.launch(scenario, seed, speed, mode, arm, experiment_id)
                await asyncio.wait_for(run.done.wait(), run.config["duration"] / speed + 60)
                if run.end_reason != "COMPLETED":
                    raise ValueError("El gemelo terminó un brazo antes de completar el escenario.")
                comparable = comparable and self.journal.rows[-1]["status"] == "complete"
            if comparable:
                self.experiment["status"] = "complete"
            else:
                self.experiment.update(status="inconclusive", note="Algún brazo perdió continuidad o supervisión.")
        except (httpx.HTTPError, ValueError, asyncio.TimeoutError) as error:
            LOG.warning("Comparación interrumpida: %s", error)
            self.experiment["status"] = "inconclusive"
            self.experiment["note"] = "La comparación se interrumpió. Consulta las corridas registradas."
            if self.run.config and self.run.config["experiment"] == experiment_id and not self.run.done.is_set():
                self.finish(self.run, "UNKNOWN", self.run.last_ms or 0)
