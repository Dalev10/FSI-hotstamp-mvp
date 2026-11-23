"""Una aplicación local para el agente y el tablero de Simex."""

import asyncio
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .agent import Agent, DEMO_SCENARIOS
from .results import Journal
from .twin_client import TwinClient

ROOT = Path(__file__).resolve().parent.parent
STATIC = Path(__file__).resolve().parent / "static"
REFERENCE = ROOT / "docs" / "demo" / "resultados.csv"
Mode = Literal["shadow", "auto"]


class RunRequest(BaseModel):
    scenario: Literal["nominal", "ruido-sensor", "frente-frio", "termocupla-abierta"]
    seed: int = Field(default=42, ge=0, le=2_147_483_647)
    speed: int = Field(default=10, ge=1, le=60)


class LaunchRequest(RunRequest):
    mode: Mode = "shadow"


class ModeRequest(BaseModel):
    mode: Mode


@asynccontextmanager
async def lifespan(app):
    twin = TwinClient()
    directory = os.environ.get("SIMEX_DATA_DIR", ROOT / ".simex-data")
    agent = app.state.agent = Agent(twin, Journal(directory, REFERENCE))
    tasks = [asyncio.create_task(agent.listen()), asyncio.create_task(agent.supervise())]
    try:
        yield
    finally:
        tasks.extend(task for task in (agent.experiment_task, agent.command_task) if task)
        agent.finish(agent.run, "UNKNOWN", agent.run.last_ms or 0)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await twin.http.aclose()


app = FastAPI(title="Simex", lifespan=lifespan, docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
async def index():
    return FileResponse(STATIC / "index.html")


@app.get("/api/status")
async def status():
    agent = app.state.agent
    run = agent.run
    snapshot = run.snapshot or {}
    comparison_rows = [row for row in agent.journal.rows if row["experiment"]]
    return {
        "connected": agent.connected, "fresh": agent.fresh(), "error": agent.last_error,
        "mode": agent.mode, "run": run.config, "runId": run.id, "ended": run.end_reason,
        "starting": agent.starting, "commandPending": bool(run.intervention and run.intervention.confirmed_ms is None),
        "simTimeMs": run.last_ms if run.last_ms is not None else snapshot.get("simTimeMs"),
        "snapshot": snapshot, "graph": list(run.graph), "marks": list(run.marks),
        "gaps": run.gaps, "watchdogTripped": run.watchdog_tripped,
        "process": agent.limits["process"] if agent.limits else None,
        "scenarios": [scenario for scenario in DEMO_SCENARIOS if any(
            entry["id"] == scenario for entry in agent.scenarios
        )],
        "decisions": list(reversed(run.history)), "experiment": agent.experiment,
        "comparison": comparison_rows[-60:],
        "hasDecisions": agent.journal.decisions.exists(),
    }


def ensure_idle(agent):
    if agent.experiment_task and not agent.experiment_task.done():
        raise HTTPException(409, "Hay una comparación en curso.")


@app.post("/api/run", status_code=201)
async def start_run(request: LaunchRequest):
    agent = app.state.agent
    ensure_idle(agent)
    try:
        run = await agent.launch(request.scenario, request.seed, request.speed, request.mode,
                                 "AGENT" if request.mode == "auto" else "SHADOW")
    except ValueError as error:
        raise HTTPException(409, str(error)) from error
    except (httpx.HTTPError, asyncio.TimeoutError) as error:
        raise HTTPException(502, "El gemelo no confirmó el arranque de la corrida.") from error
    agent.mode = request.mode
    return {"runId": run.id}


@app.post("/api/mode")
async def set_mode(request: ModeRequest):
    agent = app.state.agent
    ensure_idle(agent)
    agent.change_mode(request.mode)
    return {"mode": agent.mode}


@app.post("/api/comparison", status_code=202)
async def comparison(request: RunRequest):
    agent = app.state.agent
    ensure_idle(agent)
    if not agent.connected or agent.starting or agent.run.intervention or (
        agent.command_task and not agent.command_task.done()
    ):
        raise HTTPException(409, "Espera la conexión con el gemelo o el resultado del ajuste en curso.")
    agent.experiment_task = asyncio.create_task(agent.compare(request.scenario, request.seed, request.speed))
    return {"status": "scheduled"}


@app.get("/api/files/{kind}")
async def files(kind: Literal["resultados.csv", "decisiones.jsonl", "referencia.csv"]):
    journal = app.state.agent.journal
    path = REFERENCE if kind == "referencia.csv" else journal.directory / kind
    if not path.exists():
        raise HTTPException(404, "El archivo aún no tiene registros.")
    return FileResponse(path, filename=kind)
