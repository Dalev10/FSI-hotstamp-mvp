# FSI hotstamp MVP — gemelo digital de la estampadora

[![twin-java](https://github.com/Dalev10/FSI-hotstamp-mvp/actions/workflows/twin.yml/badge.svg)](https://github.com/Dalev10/FSI-hotstamp-mvp/actions/workflows/twin.yml)

Gemelo digital de la estampadora en caliente del ITO 2: simula la planta térmica, la banda, los sensores y la
neumática, con su lógica de control y seguridad, y expone telemetría y comandos por REST y WebSocket para el agente de
IA y el dashboard.

## Contenido

| Carpeta | Qué hay |
|---|---|
| [`twin-java/`](twin-java/) | El servicio del gemelo (Java 21, Spring Boot). Su [README](twin-java/README.md) explica el arranque, la API, los escenarios y las fallas |
| [`contracts/`](contracts/) | Contrato del API, fuente de verdad: JSON Schema, `openapi.yaml`, `asyncapi.yaml` y ejemplos |
| [`gateway/`](gateway/) | Gateway Nginx mínimo: publica el puerto 8080 y exige `X-API-Key` en comandos y simulación |
| [`docs/plans/`](docs/plans/) | Plan de desarrollo del gemelo ([PLAN-01](docs/plans/PLAN-01-gemelo-digital.md)) |

## Arranque rápido

Requiere Docker con Compose.

```bash
cp .env.example .env              # y pon una clave propia en TWIN_API_KEY (openssl rand -hex 24)
docker compose up --build -d --wait
curl localhost:8080/api/twin/state
```

La telemetría en tiempo real llega por `ws://localhost:8080/ws/telemetry`. Los comandos y el control de la simulación
necesitan la cabecera `X-API-Key`; los ejemplos están en el [README del servicio](twin-java/README.md#api).
