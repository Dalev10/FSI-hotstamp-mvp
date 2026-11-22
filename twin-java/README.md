# twin-java — gemelo digital de la estampadora

Simulación determinista de la estampadora en caliente del ITO 2 (planta térmica, banda, sensores y neumática) con su
lógica de control y seguridad. Expone telemetría y comandos por REST y WebSocket para el agente de IA y el dashboard.
La especificación completa está en [`docs/plans/PLAN-01-gemelo-digital.md`](../docs/plans/PLAN-01-gemelo-digital.md).

> Estado: **T10 (completo)**. Arranca la corrida `nominal` (semilla 42, ×1) y la expone por `/api/twin/*` y
> `/ws/telemetry` según el contrato, con métricas, logs por corrida y cierre ordenado. En el sistema corre detrás del
> gateway con `docker compose`.

## Requisitos

- JDK 21. Si `java -version` no muestra 21, exporta `JAVA_HOME` antes de usar el wrapper, por ejemplo
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- Docker, solo para construir la imagen.

## Comandos

```bash
./mvnw spotless:apply           # formatear
./mvnw verify                   # compuerta completa: compilación sin advertencias, Spotless, pruebas, ArchUnit, JaCoCo
./mvnw verify -Pperf            # además, las pruebas de rendimiento (@Tag("perf"))
./mvnw spring-boot:run          # correr local en el puerto 8080
docker build -t twin-java .     # imagen (usuario no root)
```

Comprobación rápida: `curl http://localhost:8080/actuator/health` debe responder `{"status":"UP",...}`.

## Arranque con Docker Compose

Desde la raíz del monorepo. El gateway (Nginx, en [`../gateway/`](../gateway/)) publica el puerto 8080 y reenvía
`/api/twin/*` y `/ws/telemetry` al gemelo, que no publica puertos. El gateway exige `X-API-Key` en
`/api/twin/commands` y `/api/twin/sim/*`; la clave va en `.env`, que no se versiona.

```bash
cp .env.example .env                                    # y pon una clave propia: openssl rand -hex 24
docker compose up --build -d --wait                     # espera a que el gemelo esté sano
set -a && . ./.env && set +a                            # TWIN_API_KEY para los ejemplos
curl localhost:8080/api/twin/state                      # snapshot (sin clave: es de lectura)
docker compose logs -f twin                             # logs con [runId=…]
docker compose down                                     # cierre ordenado: RUN_ENDED y cierre 1001 de los WebSocket
```

## Configuración

Todos los parámetros están en `src/main/resources/application.yml`, bajo `twin.*`, y se validan al arrancar: un valor
inválido impide el arranque. Cualquier clave se puede sobrescribir por variable de entorno, por ejemplo
`TWIN_AUTOSTART_SCENARIO=` (vacío, para no arrancar ninguna corrida) o `TWIN_AUTOSTART_SPEEDFACTOR=10`.

## Contratos

La fuente de verdad del API está en [`../contracts/`](../contracts/): esquemas JSON Schema 2020-12 (`schemas/`),
un ejemplo válido por mensaje (`examples/`), `openapi.yaml` (REST) y `asyncapi.yaml` (`/ws/telemetry`). Todo cambio de
contrato se hace primero allí. `ContractExamplesTest` valida los ejemplos, comprueba que los casos de
`src/test/resources/contract-invalid-cases.json` se rechacen y que cada `$ref` resuelva. Las pruebas leen la carpeta
desde la propiedad `contracts.dir`, que Maven fija en `../contracts`.

## API

Las rutas, cuerpos y códigos están en `openapi.yaml` y `asyncapi.yaml`; `/v3/api-docs` publica lo implementado (el
gateway no la expone) y `RestApiIntegrationTest` comprueba que coincida con el contrato. Ejemplos a través del gateway:

```bash
G=localhost:8080; K="X-API-Key: $TWIN_API_KEY"; J='Content-Type: application/json'
curl $G/api/twin/state                                  # añade ?groundTruth=true para la verdad de planta
curl $G/api/twin/limits                                 # límites de comandos
curl -H "$K" $G/api/twin/sim/scenarios
curl -H "$K" -H "$J" -X POST $G/api/twin/sim/scenario -d '{"scenarioId":"frente-frio","seed":7,"speedFactor":10}'
curl -H "$K" -H "$J" -X POST $G/api/twin/commands \
  -d '{"commandId":"'"$(uuidgen)"'","type":"SET_SETPOINT","issuedBy":"agent","params":{"setpointC":64}}'
curl -H "$K" -H "$J" -X POST $G/api/twin/commands/heartbeat \
  -d '{"agentId":"agente-1","seq":0,"sentAt":"'"$(date -u +%FT%TZ)"'"}'
curl -H "$K" $G/api/twin/sim/faults                     # catálogo y fallas activas
curl -H "$K" -H "$J" -X POST $G/api/twin/sim/faults -d '{"type":"COLD_FRONT","params":{"ambientDropC":5},"durationS":300}'
curl -H "$K" -X DELETE $G/api/twin/sim/faults/<faultId>
curl -H "$K" -H "$J" -X POST $G/api/twin/sim/control -d '{"action":"SET_SPEED","speedFactor":5}'   # PAUSE, RESUME, STOP
```

- **Comandos**: 202 si se aplican, 409 si una regla los rechaza (`rule` dice cuál), 503 si el motor no responde en
  `twin.commands.apply-timeout-ms`. Un `commandId` repetido devuelve la respuesta original con `Idempotent-Replay: true`.
  `issuedBy: agent` solo puede enviar `SET_SETPOINT`, `SET_PID` y `SET_BELT_SPEED`.
- **Errores**: RFC 9457 (`application/problem+json`) con `detail` en español: 400 para JSON o parámetros inválidos
  (los campos desconocidos también), 404 si el escenario o la falla no existen y 409 para fallas sin corrida activa.
  El gateway responde 401 sin una `X-API-Key` válida.
- **WebSocket** (`ws://localhost:8080/ws/telemetry`): `hello` al conectar y luego telemetría cada segundo simulado y
  eventos, con `seq` consecutivo por vista. Sin `?groundTruth=true` no viajan el bloque `groundTruth`, los eventos
  `FAULT_*` ni el `scenarioId` de `RUN_STARTED`. Un cliente lento no frena al motor: la cola de salida (10 000 mensajes)
  descarta la telemetría más vieja, nunca un evento, y la sesión que excede 2 s o 512 KB de envío se cierra.

### Escenarios

Todos duran 3600 s simulados y, salvo `nominal`, inyectan su falla a los 600 s.

| `scenarioId` | Qué pasa |
|---|---|
| `nominal` | Operación nominal sin fallas |
| `frente-frio` | El ambiente baja 10 °C y aumentan las pérdidas (`COLD_FRONT`) |
| `resistencia-degradada` | La resistencia pierde eficiencia, hasta 70 % en 30 min (`HEATER_DEGRADATION`) |
| `ruido-sensor` | El ruido de la termocupla se multiplica por 5 (`SENSOR_NOISE`) |
| `deriva-offset` | El offset de la termocupla deriva 0,02 mV/min, ≈ 0,5 °C/min (`OFFSET_DRIFT`) |
| `termocupla-abierta` | La termocupla se corta y la máquina pasa a `FAULT` (`THERMOCOUPLE_OPEN`) |
| `desgaste-motor` | Fricción del motor ×1,3 en 10 min (`MOTOR_WEAR`) |
| `fuga-neumatica` | Carrera ×1,5 en ambos cilindros (`PNEUMATIC_LEAK`) |
| `rafaga-metales` | 80 % de metales, uno cada 5 s, durante 120 s (`METAL_BURST`) |

### Fallas

Los parámetros omitidos toman su valor por defecto; un valor fuera de rango responde 400. Sin `durationS`, la falla
dura hasta que se retira (`METAL_BURST`: 120 s).

| `type` | Parámetros (por defecto) |
|---|---|
| `HEATER_DEGRADATION` | `finalEfficiencyFactor` (0,7; en (0, 1]), `rampS` (1800) |
| `COLD_FRONT` | `ambientDropC` (10), `rthFactor` (0,85; > 0), `rampS` (600) |
| `SENSOR_NOISE` | `sigmaFactor` (5; > 0) |
| `OFFSET_DRIFT` | `driftMvPerMin` (0,02) |
| `THERMOCOUPLE_OPEN` | — |
| `MOTOR_WEAR` | `frictionFactor` (1,3; > 0), `rampS` (600) |
| `PNEUMATIC_LEAK` | `cylinder` (obligatorio: `STAMP`, `DISCARD` o `BOTH`), `strokeFactor` (1,5; > 0) |
| `METAL_BURST` | `metalRatio` (0,8; en [0, 1]), `meanIntervalS` (5; > 0) |

## Operación

- **Métricas** (`/actuator/metrics/<nombre>`, dentro de la red de Compose): `twin.tick.duration` (duración de cada
  tick, con p50 y p99), `twin.ws.sessions` (sesiones suscritas) y `twin.telemetry.dropped` (telemetrías descartadas
  por cola llena). Ejemplo: `docker compose exec twin wget -qO- localhost:8080/actuator/metrics/twin.tick.duration`.
- **Logs**: cada línea lleva `[runId=…]` de la corrida vigente (hilo del motor, peticiones REST y difusión).
- **Cierre ordenado**: al apagar se termina la corrida (`RUN_ENDED{reason: STOPPED}`), se espera a que salga por
  WebSocket y se cierran las sesiones con `1001 GOING_AWAY`, todo dentro de `twin.engine.shutdown-timeout-ms` (5 s).

## Arquitectura

Hexagonal con un ciclo de scan tipo PLC en el dominio. Paquetes bajo `com.simex.twin`:

| Paquete | Contenido | Puede depender de |
|---|---|---|
| `domain` | Planta (`plant`) y control (`control`), que solo se comunican por la imagen de proceso (`io`) | solo JDK y JSpecify |
| `application` | Motor de simulación, casos de uso y puertos | `domain` |
| `adapter` | REST, WebSocket, escenarios y reloj; `adapter.contract` es el JSON que comparten | `application`, `domain` |
| `config` | Propiedades y cableado (`@Bean`) | todo |

`ArchitectureTest` hace cumplir estas reglas en cada `verify`.
