# twin-java — gemelo digital de la estampadora

Simulación determinista de la estampadora en caliente del ITO 2 (planta térmica, banda, sensores y neumática) con su
lógica de control y seguridad. Expone telemetría y comandos por REST y WebSocket para el agente de IA y el dashboard.
La especificación completa está en [`docs/plans/PLAN-01-gemelo-digital.md`](../docs/plans/PLAN-01-gemelo-digital.md).

> Estado: **T4 (planta simulada)**. La planta completa (térmica, medición, banda, piezas y neumática) existe como
> dominio, pero el servicio todavía no simula. El perfil `stub` imita el contrato para que el agente y el dashboard
> puedan avanzar.

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

## Perfil `stub` (temporal, se elimina en T9)

Reenvía `contracts/examples/telemetry*.json` por `/ws/telemetry` a 1 Hz, con `seq` consecutivo y `hello` al conectar.
Quita `groundTruth` salvo con `?groundTruth=true`. Responde 202 `APPLIED` a `POST /api/twin/commands` y 204 a los
latidos, sin aplicar nada.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=stub
# Con Docker hay que montar los ejemplos, porque contracts/ no entra en la imagen:
docker run --rm -p 8080:8080 -v "$PWD/../contracts:/contracts:ro" \
  -e SPRING_PROFILES_ACTIVE=stub -e TWIN_STUB_EXAMPLESDIR=/contracts/examples twin-java
```

## Arquitectura

Hexagonal con un ciclo de scan tipo PLC en el dominio. Paquetes bajo `com.simex.twin`:

| Paquete | Contenido | Puede depender de |
|---|---|---|
| `domain` | Planta (`plant`) y control (`control`), que solo se comunican por la imagen de proceso (`io`) | solo JDK y JSpecify |
| `application` | Motor de simulación, casos de uso y puertos | `domain` |
| `adapter` | REST, WebSocket, escenarios y reloj | `application`, `domain` |
| `config` | Propiedades y cableado (`@Bean`) | todo |

`ArchitectureTest` hace cumplir estas reglas en cada `verify`.
