"""Cliente de las interfaces públicas del gemelo Java."""

import json
import os

import httpx
from websockets.asyncio.client import connect


class TwinClient:
    def __init__(self):
        origin = os.environ.get("SIMEX_TWIN_URL", "http://127.0.0.1:8080").rstrip("/")
        key = os.environ.get("TWIN_API_KEY")
        self.http = httpx.AsyncClient(
            base_url=f"{origin}/api/twin/", timeout=3, trust_env=False,
            headers={"X-API-Key": key} if key else {},
        )
        self.ws_url = origin.replace("http", "ws", 1) + "/ws/telemetry"

    async def read(self, route):
        response = await self.http.get(route)
        response.raise_for_status()
        return response.json()

    async def start(self, scenario, seed, speed, control):
        response = await self.http.post("sim/scenario", json={
            "scenarioId": scenario, "seed": seed, "speedFactor": speed, "controlMode": control,
        })
        response.raise_for_status()
        return response.json()["runId"]

    async def messages(self):
        async with connect(self.ws_url, open_timeout=3, proxy=None) as socket:
            async for message in socket:
                yield json.loads(message)
