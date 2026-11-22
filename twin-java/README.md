# twin-java — gemelo digital de la estampadora

Simulación determinista de la estampadora en caliente del ITO 2 (planta térmica, banda, sensores y neumática) con su
lógica de control y seguridad. Expone telemetría y comandos por REST y WebSocket para el agente de IA y el dashboard.
La especificación completa está en [`docs/plans/PLAN-01-gemelo-digital.md`](../docs/plans/PLAN-01-gemelo-digital.md).

> Estado: **T9 (adaptadores REST y WebSocket)**. El gemelo arranca la corrida `nominal` (semilla 42, ×1) y la expone
> por `/api/twin/*` y `/ws/telemetry` según el contrato. Falta la operación (métricas, apagado ordenado y Compose: T10).

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

## Configuración

Todos los parámetros están en `src/main/resources/application.yml`, bajo `twin.*`, y se validan al arrancar: un valor
inválido impide el arranque. Cualquier clave se puede sobrescribir por variable de entorno, por ejemplo
`TWIN_AUTOSTART_SCENARIO=` (vacío, para no arrancar ninguna corrida) o `TWIN_ENGINE_SPEEDFACTOR=10`.

## Contratos

La fuente de verdad del API está en [`../contracts/`](../contracts/): esquemas JSON Schema 2020-12 (`schemas/`),
un ejemplo válido por mensaje (`examples/`), `openapi.yaml` (REST) y `asyncapi.yaml` (`/ws/telemetry`). Todo cambio de
contrato se hace primero allí. `ContractExamplesTest` valida los ejemplos, comprueba que los casos de
`src/test/resources/contract-invalid-cases.json` se rechacen y que cada `$ref` resuelva. Las pruebas leen la carpeta
desde la propiedad `contracts.dir`, que Maven fija en `../contracts`.

## API

Las rutas, cuerpos y códigos están en `openapi.yaml` y `asyncapi.yaml`; `/v3/api-docs` publica lo implementado y
`RestApiIntegrationTest` comprueba que coincida con el contrato. Ejemplos:

```bash
curl localhost:8080/api/twin/state                     # snapshot (añade ?groundTruth=true para la verdad de planta)
curl localhost:8080/api/twin/limits                    # límites de comandos
curl -X POST localhost:8080/api/twin/sim/scenario -H 'Content-Type: application/json' \
  -d '{"scenarioId":"frente-frio","seed":7,"speedFactor":10}'
curl -X POST localhost:8080/api/twin/commands -H 'Content-Type: application/json' \
  -d '{"commandId":"'"$(uuidgen)"'","type":"SET_SETPOINT","issuedBy":"agent","params":{"setpointC":64}}'
curl -X POST localhost:8080/api/twin/sim/faults -H 'Content-Type: application/json' -d '{"type":"COLD_FRONT"}'
```

- **Comandos**: 202 si se aplican, 409 si una regla los rechaza (`rule` dice cuál), 503 si el motor no responde en
  `twin.commands.apply-timeout-ms`. Un `commandId` repetido devuelve la respuesta original con `Idempotent-Replay: true`.
- **Errores**: RFC 9457 (`application/problem+json`) con `detail` en español: 400 para JSON o parámetros inválidos
  (los campos desconocidos también), 404 si el escenario o la falla no existen y 409 para fallas sin corrida activa.
- **WebSocket** (`/ws/telemetry`): `hello` al conectar y luego telemetría cada segundo simulado y eventos, con `seq`
  consecutivo por vista. Sin `?groundTruth=true` no viajan el bloque `groundTruth`, los eventos `FAULT_*` ni el
  `scenarioId` de `RUN_STARTED`. Un cliente lento no frena al motor: la cola de salida (10 000 mensajes) descarta la
  telemetría más vieja, nunca un evento, y la sesión que excede 2 s o 512 KB de envío se cierra.

## Arquitectura

Hexagonal con un ciclo de scan tipo PLC en el dominio. Paquetes bajo `com.simex.twin`:

| Paquete | Contenido | Puede depender de |
|---|---|---|
| `domain` | Planta (`plant`) y control (`control`), que solo se comunican por la imagen de proceso (`io`) | solo JDK y JSpecify |
| `application` | Motor de simulación, casos de uso y puertos | `domain` |
| `adapter` | REST, WebSocket, escenarios y reloj; `adapter.contract` es el JSON que comparten | `application`, `domain` |
| `config` | Propiedades y cableado (`@Bean`) | todo |

`ArchitectureTest` hace cumplir estas reglas en cada `verify`.
