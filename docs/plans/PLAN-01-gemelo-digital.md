# Plan de trabajo 01: microservicio `twin-java` (gemelo digital)

> **Cómo usar este plan (instrucciones para Claude Code)**
> 1. Lee el documento completo antes de escribir código. Las secciones 3 a 9 son la especificación y la sección 11 es el orden de trabajo.
> 2. Ejecuta **una iteración a la vez** (T0 → T10). Al terminar cada una, corre `./mvnw verify`, resume los cambios y **detente a esperar aprobación** antes de seguir.
> 3. Si algo del plan es ambiguo o contradictorio, pregunta antes de decidir. No agregues dependencias que no estén en la sección 2 sin consultarlo.
> 4. Las reglas de la sección 13 se cumplen siempre, aunque una tarea no las repita.

Ubicación sugerida en el repositorio: `docs/plans/01-twin-java.md`.

---

## 1. Objetivo y alcance

**Objetivo.** Construir un gemelo digital determinista de la estampadora en caliente (ITO 2): física térmica, banda, sensores y neumática simulados, con la lógica de control y seguridad de la máquina. Debe exponer por contrato (REST + WebSocket) la telemetría y los comandos que consumen el agente de IA (Python) y el dashboard, de modo que más adelante un ESP32 o un PLC pueda reemplazarlo implementando el mismo contrato.

**Responsabilidades del servicio**
- Simular la planta en pasos fijos de 100 ms de tiempo simulado, con acelerador ×1 a ×60 y semilla reproducible.
- Ejecutar el control local: secuencia (máquina de estados), control térmico (LEGACY o PID con SSR), interlocks y watchdog.
- Validar y aplicar comandos sin obedecer nunca fuera de límites.
- Publicar telemetría (cada 1 s simulado) y eventos (inmediatos) por WebSocket.
- Inyectar fallas y correr escenarios con semilla.

**Fuera de alcance**
- Persistencia: el gemelo no tiene base de datos (el agente persiste).
- Autenticación: la clave de API la valida el gateway Nginx; el gemelo no publica puertos.
- Lógica de IA, diagnóstico o re-sintonía: son del agente.
- Interfaz gráfica: es del dashboard.

**Contexto del sistema** (solo lo necesario): `gateway` (Nginx, puerto 8080 publicado) enruta `/api/twin/*` y `/ws/telemetry` hacia `twin:8080` **conservando la ruta** (por eso el gemelo sirve sus endpoints con el prefijo `/api/twin`). El gateway exige `X-API-Key` en `^/api/twin/(commands|sim/)`. El agente se conecta a `ws://gateway/ws/telemetry` y a `http://gateway/api/twin`.

---

## 2. Stack y dependencias

| Componente | Elección | Nota |
|---|---|---|
| Lenguaje | Java 21 (LTS) | Records, sealed interfaces, pattern matching en `switch`, hilos virtuales |
| Framework | Spring Boot 4.x, la última versión estable (GA) que ofrezca start.spring.io | Nunca milestones ni RC. La línea 3.5 terminó su soporte OSS en junio de 2026 |
| Build | Maven con wrapper (`./mvnw`) | `groupId=com.simex`, `artifactId=twin-java`, paquete raíz `com.simex.twin` |
| Starters | Spring Web MVC, WebSocket, Validation, Actuator, Configuration Processor | Generar con start.spring.io; deja que el inicializador añada los starters de prueba correspondientes |
| JSON | Jackson 3 (el que trae Spring Boot 4) | Paquetes `tools.jackson.*`; las anotaciones siguen en `com.fasterxml.jackson.annotation` |
| Nulabilidad | JSpecify (`@NullMarked` en cada `package-info.java`) | Única dependencia permitida en `domain` además del JDK |
| Documentación de API | springdoc-openapi 3.x (`springdoc-openapi-starter-webmvc-api`) | Solo para comparar contra el contrato; la fuente de verdad es `contracts/openapi.yaml` |
| Pruebas | JUnit (versión gestionada por Boot), AssertJ, Mockito, Awaitility | Usa `@MockitoBean` (no `@MockBean`) |
| Arquitectura | ArchUnit 1.5.x (`archunit-junit5`) | Reglas de la sección 9.3 |
| Contratos en pruebas | `com.networknt:json-schema-validator` (estable más reciente, scope `test`) | Valida mensajes salientes contra JSON Schema 2020-12. Si trae Jackson 2, se acepta solo en `test` |
| Formato | Spotless + palantir-java-format | `./mvnw spotless:apply`; `spotless:check` en `verify` |
| Cobertura | JaCoCo | Umbral: `domain` ≥ 85 % de líneas; total ≥ 70 % |
| Contenedor | Construcción con `eclipse-temurin:21-jdk-alpine`, ejecución con `eclipse-temurin:21-jre-alpine` | Usuario no root |

Prohibido: Lombok, MapStruct, generadores de código a partir del esquema (los DTO se escriben a mano como records y se verifican con pruebas de contrato) y bases de datos.

---

## 3. Arquitectura

### 3.1 Estilo

**Hexagonal (puertos y adaptadores) con un ciclo de scan tipo PLC dentro del dominio.**
- El **dominio** es Java puro y contiene dos mundos separados:
  - **Planta** (`domain.plant`): lo que en la realidad sería la máquina física (termodinámica, banda, sensores, neumática).
  - **Control** (`domain.control`): lo que en la realidad sería el firmware o PLC (secuencia, control térmico, seguridad, reglas de comandos).
- Planta y control solo se comunican mediante una **imagen de proceso** (`ProcessInputs` / `ProcessOutputs`) y el puerto `PlantPort`, igual que un PLC lee entradas y escribe salidas. Así la lógica de control se prueba contra una planta falsa y la planta simulada podría reemplazarse por E/S reales.
- **Aplicación** (`application`): orquesta tiempo, corridas, cola de comandos e idempotencia. También es Java puro.
- **Adaptadores** (`adapter`): REST y WebSocket (Spring), repositorio de escenarios y reloj de pared.
- **Configuración** (`config`): el único lugar donde se instancian y conectan los objetos (`@Bean`).

### 3.2 Regla de dependencias

```
adapter.in.rest ──► application.port.in ◄── application.service / engine ──► application.port.out ◄── adapter.out.*
                                   │                                                          
                                   ▼                                                          
                                domain  (machine, control, plant, io, fault, scenario, event, shared)
config ──► todo (solo para cablear)
```
- `domain` no depende de nada del proyecto fuera de `domain`, ni de Spring, Jackson o Jakarta.
- `application` depende solo de `domain` (sin Spring).
- `adapter.in` y `adapter.out` no dependen entre sí.
- Dentro de `domain`: `control` y `plant` no se conocen; ambos dependen de `io` y `shared`.

### 3.3 Estructura de paquetes

```
twin-java/
├── pom.xml, mvnw, .mvn/, Dockerfile, .dockerignore, README.md
└── src/
    ├── main/java/com/simex/twin/
    │   ├── TwinApplication.java
    │   ├── domain/
    │   │   ├── shared/      SimTime, SimDuration, Celsius, Percent, DutyCycle, RandomStreams, DomainException
    │   │   ├── io/          ProcessInputs, ProcessOutputs, PlantPort, CylinderPosition, ValveCommand
    │   │   ├── plant/
    │   │   │   ├── thermal/     ThermalPlant, ThermalParameters, DelayLine, FirstOrderLag
    │   │   │   ├── sensing/     ThermocoupleChain, SignalStage (+ etapas), Adc, AmbientSensor
    │   │   │   ├── conveyor/    Conveyor, Motor, Piece, PieceType, ArrivalGenerator, InductiveSensor, PhotoelectricSensor, ExitInspection
    │   │   │   ├── pneumatics/  PneumaticCylinder
    │   │   │   └── SimulatedPlant, PlantModifiers
    │   │   ├── control/
    │   │   │   ├── thermal/     TemperatureController, LegacyLogic, LegacyOnOffController, PidController, TimeProportionalSsr
    │   │   │   ├── sequence/    MachineState, StateHandler + un handler por estado, Sequencer, SequenceContext
    │   │   │   ├── safety/      Interlock, InterlockChain, EmergencyStopInterlock, ThermocoupleOpenInterlock,
    │   │   │   │                OverTemperatureInterlock, PneumaticTimeoutInterlock, Watchdog, SafeParameters
    │   │   │   ├── alarm/       AlarmCode, Severity, ActiveAlarm, AlarmRegistry
    │   │   │   ├── command/     MachineCommand (sealed), CommandRule, CommandValidator, CommandResult, Rule (códigos)
    │   │   │   └── ControlLogic          (une secuencia + térmico + seguridad en un scan)
    │   │   ├── fault/       Fault, FaultType, FaultFactory, FaultInjector, faults/*
    │   │   ├── scenario/    Scenario, ArrivalProfile, AmbientProfile, ScheduledAction, ScheduledFault
    │   │   ├── event/       DomainEvent (sealed) + records
    │   │   └── machine/     StampingMachine (raíz del agregado), MachineSnapshot, Counters, EnergyMeter, ScanResult
    │   ├── application/
    │   │   ├── port/in/     ApplyCommandUseCase, HeartbeatUseCase, SimulationControlUseCase, FaultInjectionUseCase, QueryStateUseCase
    │   │   ├── port/out/    TelemetryPublisher, ScenarioRepository, WallClock
    │   │   ├── engine/      SimulationEngine (síncrono), EngineScheduler (hilo), CommandInbox, PendingCommand, IdempotencyStore, RunContext
    │   │   └── service/     implementaciones delgadas de los casos de uso, TelemetryAssembler
    │   ├── adapter/
    │   │   ├── in/rest/          CommandController, HeartbeatController, SimulationController, StateController, dto/, RestExceptionHandler
    │   │   ├── out/websocket/    TelemetryWebSocketHandler, WebSocketTelemetryPublisher, SessionRegistry, message/
    │   │   ├── out/scenario/     ClasspathScenarioRepository
    │   │   └── out/clock/        SystemWallClock
    │   └── config/          TwinProperties (@ConfigurationProperties), DomainConfig, ApplicationConfig, WebSocketConfig
    ├── main/resources/      application.yml, scenarios/*.json
    └── test/java/com/simex/twin/   (espejo de main) + architecture/ + contract/ + golden/
```

### 3.4 Ciclo de scan (orden exacto por tick de 100 ms simulados)

`StampingMachine.scan(SimDuration dt)` hace, en este orden:
1. `inputs = plant.readInputs()`: muestreo de sensores con su ruido, ADC y estados de fin de carrera.
2. `outputs = controlLogic.scan(inputs, now)`, que a su vez:
   1. evalúa la `InterlockChain` (por prioridad);
   2. si nada dispara, ejecuta el `Sequencer` (estado actual y transiciones);
   3. ejecuta el `TemperatureController` activo y luego el `TimeProportionalSsr`;
   4. aplica las sobreescrituras de seguridad (si hay disparo, salidas seguras).
3. `plant.writeOutputs(outputs)`: SSR, PWM del motor y válvulas.
4. `plant.advance(dt)`: integra la física (térmica, banda, piezas, cilindros) y aplica `PlantModifiers` de las fallas activas.
5. Actualiza contadores y energía, y recoge los `DomainEvent` del tick en orden.
6. Devuelve `ScanResult(snapshot, events)`.

Los comandos pendientes se aplican **antes** del paso 1, en el orden de llegada.

### 3.5 Concurrencia (escritor único)

- **Hilo `sim-engine`** (un `ScheduledExecutorService` de un solo hilo, tasa fija de 100 ms de pared). Es el **único** que muta el dominio. En cada ciclo:
  1. drena `CommandInbox` y controles de simulación (también con la simulación en pausa);
  2. si la corrida está activa, ejecuta `speedFactor` ticks;
  3. publica la telemetría en cada frontera de 1 s simulado y los eventos al momento;
  4. publica el `MachineSnapshot` inmutable en un `AtomicReference`.
- **Hilos REST** (hilos virtuales):
  1. validan la sintaxis;
  2. consultan la idempotencia;
  3. encolan un `PendingCommand(command, CompletableFuture<CommandResult>)`;
  4. esperan el resultado hasta `apply-timeout-ms`.

  Las lecturas (`GET /state`) usan el snapshot sin bloquear.
- **Hilo `ws-broadcaster`**: consume una cola acotada (`outbound-queue-capacity`) y envía a cada sesión envuelta en `ConcurrentWebSocketSessionDecorator` (límite de tiempo de envío 2 s, búfer de 512 KB). El motor nunca espera a un cliente lento. Si la cola se llena, se descarta la telemetría más antigua (nunca un evento) y se incrementa la métrica `twin.telemetry.dropped`.
- `SimulationEngine` es **síncrono** (`advance(int ticks)`) y no sabe de hilos; `EngineScheduler` es quien lo mueve. Así las pruebas corren la simulación sin hilos ni esperas.
- Modo `MAX` (solo pruebas y uso interno): avanza sin dormir, en bloques de 1.000 ticks, drenando la bandeja entre bloques.

### 3.6 Tiempo y determinismo

- `SimTime` cuenta milisegundos simulados en un `long`. El dominio nunca consulta el reloj del sistema.
- `WallClock` (puerto) solo lo usan el watchdog y el sello `ts`. En pruebas se usa `FakeWallClock`.
- `RandomStreams(seed)` entrega flujos independientes por nombre: `arrivals`, `pieceType`, `inductiveMiss`, `tcNoise`, `ambientNoise`. Cada flujo es un `SplittableRandom(mix64(seed ^ fnv1a64(name)))`, así que agregar un flujo nuevo no altera los demás.
- La gaussiana se calcula con Box–Muller y `StrictMath` (no `nextGaussian()`). En el dominio, `exp`, `log`, `sin` y `cos` también usan `StrictMath`.
- Ninguna iteración de `HashMap`/`HashSet` debe influir en el orden de cálculo o de eventos: usa `List`, `EnumMap` o `LinkedHashMap`.
- Misma semilla, mismo escenario y misma secuencia de comandos producen la misma serie de mensajes, sin contar `ts`, `simTs` ni `runId`.

---

## 4. Especificación del dominio

Todos los parámetros vienen de `TwinProperties` (sección 6) y se pasan al dominio como records inmutables (`ThermalParameters`, `SensingParameters`, etc.). En el dominio no hay números mágicos.

### 4.1 Planta térmica

Masa concentrada con retardo de transporte y retardo del sensor:

```
C · dT/dt = η_eff · P · u(t − θ_d) − (T − T_amb_eff) / R_th_eff − q_pieza(t)
dT_s/dt   = (T − T_s) / τ_s                    (temperatura en la punta de la termocupla)
```
- `u ∈ {0,1}` es el estado del SSR; `θ_d` = `transport-delay-ms` (`DelayLine`: búfer circular de ticks).
- `η_eff = heater-efficiency × modificador de falla`. `R_th_eff` y `T_amb_eff` también reciben modificadores.
- `q_pieza = piece-heat-j / stamp-dwell-ms` mientras el troquel está en contacto (estado ESTAMPADO, fase de permanencia).
- Integración: Euler explícito con `dt = 0,1 s` (estable porque τ = R·C = 920 s ≫ dt).
- La energía eléctrica se acumula como `P · u · dt` (sin η), en Wh.

### 4.2 Cadena de medición (patrón pipeline)

Etapas en orden (`SignalStage`):
1. Termopar tipo K lineal: `V_tc = S · (T_s − T_cj)`, con `S = 0,041 mV/°C` y `T_cj = T_amb` real.
2. Offset de entrada: `+ input-offset-mv + deriva de falla`.
3. Ruido: `+ N(0, σ_T · S)` con `σ_T = noise-sigma-c × factor de falla`.
4. Amplificador LM358: `× amplifier-gain` (250), con saturación entre 0 V y `adc-vref-v`.
5. ADC de 12 bits: `counts = round(V / vref × 4095)`, limitado a 0…4095.
6. Firmware: `T_meas = counts × vref / 4095 / gain / S + T_cj_comp`, donde `T_cj_comp` es la lectura del sensor de ambiente (`cold-junction: AMBIENT_SENSOR`) o `fixed-cold-junction-c` (`FIXED`).

Termocupla abierta (falla): el amplificador satura y `counts = 4095`, de modo que `T_meas ≈ 347 °C`. El sensor de ambiente devuelve `T_amb + N(0, 0,1)`. La telemetría reporta `tcMillivolts` = la tensión de entrada reconstruida desde `counts`.

### 4.3 Banda, piezas y sensores (eventos discretos sobre posición)

- Geometría (m): longitud 2,0; inductivo y pistón de descarte en 0,60; fotoeléctrico y estación de estampado en 1,50; inspección de salida en 1,95. Las piezas miden 0,06 m.
- **Llegadas**: tiempo entre llegadas exponencial con media `meanIntervalS` del escenario. El tipo es METAL con probabilidad `metalRatio`. Una pieza entra en x = 0 solo si el tramo `[0, min-spacing-m]` está libre; si no, espera en una cola de entrada (`queueLength`). Con la banda detenida no entra nada.
- **Motor**: `v_obj = nominal-speed-mps × pwm / frictionFactor`. La velocidad real se acerca a `v_obj` con aceleración máxima `accel-mps2` (también al frenar). Potencia: `motor-rated-w × pwm × frictionFactor`, acumulada en Wh.
- **Inductivo** (NPN): vale 1 si una pieza METAL se superpone a x = 0,60 y no fue sorteada como “no detectada”. La no detección se sortea **una vez por pieza**, al pasar por el sensor, con `p_miss(v) = base + gain × max(0, v − v_ref)/v_ref`, que modela el tiempo de permanencia y la frecuencia de conmutación. Las piezas plásticas dan 0.
- **Fotoeléctrico**: vale 1 si cualquier pieza se superpone a x = 1,50.
- **Inspección de salida** (supuesto del MVP): cuando una pieza sale en x = 1,95, se emite `PIECE_EXITED` con `result = STAMPED_OK` (plástica estampada) o `METAL_ESCAPED` (metal que pasó).
- **Descarte**: cuando el cilindro de descarte llega a EXTENDED, se retira la pieza METAL cuyo centro quede entre `inductive-at + discard-face-from` e `inductive-at + discard-face-to`. Si no hay ninguna (sobrepaso), el metal sigue y terminará como `METAL_ESCAPED`.

### 4.4 Neumática

- `PneumaticCylinder` con estados RETRACTED, EXTENDING, EXTENDED y RETRACTING; tiempo de carrera `stroke-ms × strokeFactor` (falla de fuga).
- Válvula 5/2 monoestable: energizada = extender; desenergizada = retraer (retorno por resorte).
- Los fines de carrera son entradas de `ProcessInputs`. Si un movimiento no termina en `stroke-timeout-ms`, el interlock neumático dispara (4.7).

### 4.5 Control térmico (patrón Strategy)

`TemperatureController` (interfaz): `Percent compute(ControllerInput in)`, `void reset(ControllerState s)` y `ControllerSnapshot snapshot()`. Contrato común (se verifica con pruebas de contrato, sección 9):
- la salida siempre está en [0, 100] %;
- es determinista;
- no tiene efectos fuera de su estado.

**LEGACY (réplica del ITO 2).** `LegacyLogic.evaluate(start, presence, inductive, tempReached)` es una función pura que reproduce las 16 filas de la tabla de verdad del ITO 2, con las salidas Metal, Plástico, Verde, Resistencia, Alarma y Motor. En el código Wokwi, la columna rotulada “Paro” de la tabla corresponde a `spresencia` (fotoeléctrico); por eso aquí se llama `presence`. El paro de emergencia real lo maneja la capa de seguridad.

Ecuaciones (verificadas contra la tabla):
```
Metal       = S ∧ SP ∧ SI
Plástico    = S ∧ SP ∧ ¬SI ∧ T
Verde       = S ∧ T
Resistencia = S ∧ ¬T ∧ (¬SI ∨ SP)
Alarma      = S ∧ ¬SP ∧ SI
Motor       = S ∧ (SP ⇔ SI)
```
- `LegacyOnOffController` usa **solo la salida Resistencia** y la evalúa cada `legacy-sample-ms` (1.010 ms, el `delay(1000)+delay(10)` del sketch), manteniendo la salida entre muestreos. Mapeo de entradas:
  - `S`: máquina en un estado operativo;
  - `SP`: fotoeléctrico;
  - `SI`: inductivo;
  - `T`: `T_meas ≥ legacy-threshold-c` (50 °C), interpretación de la tabla de E/S del ITO 2: T < 50 → SSR = 1.
- La secuencia, los pistones y la seguridad son **los mismos** en ambos modos, para que la comparación A/B aísle la estrategia térmica.
- En LEGACY se ignoran setpoint y ganancias; los comandos `SET_SETPOINT`/`SET_PID` se rechazan con `MODE_MISMATCH`.

**PID (forma paralela, salida en %).**
```
u = kp·e + I − kd·dT_meas/dt_f        e = setpoint − T_meas
```
- Período de muestreo: `sample-ms` (1 s).
- Derivada **sobre la medición**, con filtro de primer orden `τ_f = (kd/kp)/N`.
- **Anti-windup** por integración condicional: no se integra si la salida está saturada y el error empuja en el mismo sentido.
- **Cambio de ganancias sin salto**: `I_nuevo = I_viejo + (kp_viejo − kp_nuevo)·e`, de modo que la salida no salta.
- **Paso de LEGACY a PID sin salto**: se inicializa `I` para que `u` sea igual a la salida vigente (0 o 100 %).
- Unidades: `kp` en %/°C, `ki` en %/(°C·s), `kd` en %·s/°C.

**SSR proporcional en el tiempo.** Ventana de `ssr-window-ms` (2 s = 20 ticks). Al inicio de cada ventana: `onTicks = round(duty·20 + carry)` y `carry = duty·20 + carry − onTicks` (difusión del error, para que el ciclo útil medio sea exacto). El SSR queda encendido los primeros `onTicks` ticks. `heaterDuty` reporta el ciclo útil latcheado.

### 4.6 Máquina de estados (patrón State)

Cada estado es un `StateHandler` con `onEnter`, `onScan` y `onExit`. `onScan` devuelve `Transition(next)` o `Stay`. La tabla de transiciones también se prueba como datos.

| Estado (contrato) | Español | Banda | Térmico | Pistones | Transiciones |
|---|---|---|---|---|---|
| `OFF` | APAGADO | parada | apagado | retraídos | `START` → `HEATING` |
| `HEATING` | CALENTANDO | parada | activo | retraídos | T en ventana estable `stable-ms` → `TRANSPORT`; `STOP` → `OFF`; alarma `HEATUP_TIMEOUT` si pasa `heatup-timeout-ms` |
| `TRANSPORT` | TRANSPORTE | marcha a `pwm` | activo | retraídos | flanco ↑ inductivo → `DISCARD`; flanco ↑ fotoeléctrico con T en ventana → `STAMPING`, fuera de ventana → `THERMAL_WAIT`; `STOP` → `OFF` |
| `DISCARD` | DESCARTE | frena hasta 0 | activo | descarte: extender al detenerse, retraer al llegar | descarte RETRACTED → `TRANSPORT` (o `OFF` si había `STOP` pendiente) |
| `THERMAL_WAIT` | ESPERA TÉRMICA | parada | activo | retraídos | T en ventana estable `stable-ms` → `STAMPING`; alarma `THERMAL_WAIT_LONG` si pasa `thermal-wait-alarm-ms`; `STOP` → `OFF` |
| `STAMPING` | ESTAMPADO | parada | activo | estampado: extender → permanencia `stamp-dwell-ms` → retraer | estampado RETRACTED → `TRANSPORT` y evento `PIECE_STAMPED` (o `OFF` si había `STOP` pendiente) |
| `EMERGENCY_STOP` | PARO | parada | SSR apagado | válvulas desenergizadas | `RESET` con alarmas reconocidas → `OFF` |
| `FAULT` | FALLA | parada | SSR apagado | válvulas desenergizadas | `RESET` con causa despejada y alarmas reconocidas → `OFF` |

- “T en ventana” significa `window-min-c ≤ T_meas ≤ window-max-c`.
- Las detecciones se disparan por **flanco de subida**. Mientras la banda está detenida no hay nuevos disparos.
- `STOP` es inmediato en `HEATING`, `TRANSPORT` y `THERMAL_WAIT`; en `DISCARD` y `STAMPING` queda pendiente hasta terminar el ciclo del pistón.
- Cada cambio de estado emite `STATE_CHANGED`.

### 4.7 Seguridad (Chain of Responsibility)

`InterlockChain` evalúa en este orden de prioridad y se detiene en el primer disparo:

| Prioridad | Interlock | Condición | Efecto |
|---|---|---|---|
| 1 | `EmergencyStopInterlock` | paro de emergencia enclavado | → `EMERGENCY_STOP`, alarma `EMERGENCY_STOP` |
| 2 | `ThermocoupleOpenInterlock` | `counts ≥ open-tc-counts-threshold` | → `FAULT`, `THERMOCOUPLE_OPEN` |
| 3 | `OverTemperatureInterlock` | `T_meas ≥ over-temp-trip-c` | → `FAULT`, `OVER_TEMPERATURE` |
| 4 | `PneumaticTimeoutInterlock` | carrera > `stroke-timeout-ms` | → `FAULT`, `PNEUMATIC_TIMEOUT` (detalle: cilindro) |

- Salidas seguras: SSR apagado, PWM en 0, válvulas desenergizadas. Se imponen **en el mismo scan** del disparo.
- **Watchdog** (no es un interlock de disparo):
  - Se arma con el primer latido.
  - Si `wallNow − lastHeartbeat > watchdog-timeout-ms`, aplica `SafeParameters` (setpoint `default-setpoint-c`, PID por defecto, `pwm = default-pwm`), levanta `WATCHDOG_TIMEOUT` (una sola vez) y pone `supervisor = NONE` con el evento `SUPERVISOR_CHANGED`.
  - Al volver los latidos, `supervisor = AGENT` y la alarma se despeja sola. Los parámetros seguros se mantienen hasta que el agente envíe nuevos.

### 4.8 Alarmas

| Código | Severidad | Enclavada | Requiere reconocimiento | Se despeja sola |
|---|---|---|---|---|
| `TEMP_LOW` | WARNING | no | no | sí (con histéresis `band-hysteresis-c`) |
| `TEMP_HIGH` | WARNING | no | no | sí |
| `HEATUP_TIMEOUT` | WARNING | no | no | al salir de `HEATING` |
| `THERMAL_WAIT_LONG` | WARNING | no | no | al salir de `THERMAL_WAIT` |
| `WATCHDOG_TIMEOUT` | WARNING | no | no | al volver los latidos |
| `EMERGENCY_STOP` | CRITICAL | sí | sí | no |
| `THERMOCOUPLE_OPEN` | CRITICAL | sí | sí | no |
| `OVER_TEMPERATURE` | CRITICAL | sí | sí | no |
| `PNEUMATIC_TIMEOUT` | CRITICAL | sí | sí | no |

- `TEMP_LOW` y `TEMP_HIGH` solo se evalúan en `TRANSPORT`, `DISCARD`, `THERMAL_WAIT` y `STAMPING`.
- Banda del semáforo (`thermal.band`): `LOW` < 50 ≤ `OK` ≤ 75 < `HIGH`, con histéresis de 0,5 °C.

### 4.9 Comandos (patrón Command) y reglas

`MachineCommand` es una sealed interface con records:
- `SetSetpoint(setpointC)`
- `SetPid(kp, ki, kd)`
- `SetBeltSpeed(pwmDuty)`
- `SetMode(LEGACY|PID)`
- `Start`
- `Stop`
- `EmergencyStop`
- `Reset`
- `AckAlarm(code | ALL)`

Cada uno viaja con `commandId` (UUID), `issuedBy` (`agent` | `dashboard` | `scenario`) y `reason` opcional.

`CommandValidator` ejecuta una lista de `CommandRule` (cada regla es una clase pequeña). La primera violación devuelve `REJECTED` con su código:

| Código | Regla |
|---|---|
| `SOURCE_NOT_ALLOWED` | `agent` solo puede enviar `SET_SETPOINT`, `SET_PID` y `SET_BELT_SPEED` |
| `STATE_NOT_ALLOWED` | `START` solo desde `OFF`; `RESET` solo desde `EMERGENCY_STOP`/`FAULT`; ajustes no permitidos en `EMERGENCY_STOP`/`FAULT` |
| `MODE_MISMATCH` | `SET_SETPOINT`/`SET_PID` en modo LEGACY |
| `SETPOINT_RANGE` | setpoint fuera de [`window-min-c`, `window-max-c`] |
| `SETPOINT_MAX_STEP` | \|Δsetpoint\| > `setpoint-max-step-c` |
| `KP_RANGE`, `KI_RANGE`, `KD_RANGE` | fuera de los límites configurados |
| `KP_MAX_STEP_20PCT`, `KI_MAX_STEP_20PCT`, `KD_MAX_STEP_20PCT` | \|Δ\| mayor que el más grande entre el 20 % de \|actual\| y el 5 % de (máx − mín) |
| `BELT_PWM_RANGE`, `BELT_PWM_MAX_STEP` | fuera de [0,4, 1,0] o \|Δ\| > 0,2 |
| `ALARMS_NOT_ACKNOWLEDGED` | `RESET` con alarmas críticas sin reconocer |
| `CAUSE_ACTIVE` | `RESET` mientras persiste la condición que disparó |

Si el comando es válido, se aplica en el mismo tick y emite `COMMAND_APPLIED`; si no, emite `COMMAND_REJECTED`. Ambos eventos incluyen `commandId`, `type`, `issuedBy` y `rule`.

### 4.10 Fallas

`Fault` (interfaz): `void contribute(PlantModifiers m, SimDuration sinceStart)`. Es una función pura del tiempo transcurrido, lo que la hace determinista. `FaultInjector` mantiene las fallas activas, compone sus contribuciones en cada tick (los factores se multiplican y los desplazamientos se suman) y retira las que vencen. `FaultFactory` construye cada falla a partir de `(FaultType, params)`, con un registro por tipo.

| `FaultType` | Parámetros (valor por defecto) | Efecto |
|---|---|---|
| `HEATER_DEGRADATION` | `finalEfficiencyFactor` 0,70; `rampS` 1800 | η × factor, rampa lineal |
| `COLD_FRONT` | `ambientDropC` 10; `rthFactor` 0,85; `rampS` 600 | T_amb − caída; R_th × factor |
| `SENSOR_NOISE` | `sigmaFactor` 5 | σ_T × factor |
| `OFFSET_DRIFT` | `driftMvPerMin` 0,02 | offset += deriva × minutos (≈ 0,5 °C/min) |
| `THERMOCOUPLE_OPEN` | — | ADC saturado (4095) |
| `MOTOR_WEAR` | `frictionFactor` 1,30; `rampS` 600 | v_obj ÷ factor; potencia × factor |
| `PNEUMATIC_LEAK` | `cylinder` STAMP\|DISCARD\|BOTH; `strokeFactor` 1,5 | carrera × factor |
| `METAL_BURST` | `metalRatio` 0,8; `meanIntervalS` 5; `durationS` 120 | anula el perfil de llegadas mientras dura |

Cada falla tiene `faultId`, `startAtSimMs` y `durationS` opcional (si falta, dura hasta que se retira). Emiten `FAULT_INJECTED` y `FAULT_CLEARED`.

### 4.11 Escenarios y corridas

- Un `Scenario` (JSON validado al cargar) contiene: `id`, `description`, `seed`, `durationSimS`, `controlMode`, `initialConditions{platenTempC, ambientC}`, `arrivals{meanIntervalS, metalRatio}`, `ambientProfile[{atS, ambientC}]` (lineal por tramos), `actions[{atS, command}]` y `faults[{atS, type, params, durationS?}]`.
- Archivos en `src/main/resources/scenarios/`: `nominal`, `resistencia-degradada`, `frente-frio`, `ruido-sensor`, `deriva-offset`, `termocupla-abierta`, `desgaste-motor`, `fuga-neumatica` y `rafaga-metales`. Todos comienzan con la acción `START` en `atS: 0`, y cada uno, salvo `nominal`, inyecta su falla homónima a los 600 s.
- Una **corrida** (`RunContext`) empieza con `POST /sim/scenario` o con el autoarranque.
  - Asigna `runId` (UUID aleatorio, fuera del dominio), reinicia la máquina, los contadores y la energía, y emite `RUN_STARTED{scenarioId, seed, controlMode, speedFactor, parameters}`.
  - Termina al cumplir `durationSimS` o con `STOP`, y emite `RUN_ENDED{reason: COMPLETED|STOPPED|REPLACED}`.
  - Al terminar, el motor queda en pausa con el último snapshot.

### 4.12 Contadores, energía y eventos de dominio

- `Counters`: `metalDiscarded` (con `PIECE_DISCARDED`), `plasticStamped` y `metalEscaped` (ambos con `PIECE_EXITED`), y `piecesArrived`.
- `EnergyMeter`: `heaterWh` y `motorWh`.
- Solo para `groundTruth`: `stampedOutOfWindow`, el número de estampados en los que la temperatura **real** del troquel estaba fuera de la ventana (muestra el efecto de las fallas de medición).
- `DomainEvent` (sealed):
  - `PieceDiscarded`
  - `PieceStamped{pieceId, tempC, waitMs, stampStrokeMs}`
  - `PieceExited{pieceId, result}`
  - `StateChanged{from, to}`
  - `AlarmRaised` / `AlarmCleared{code, severity}`
  - `CommandApplied` / `CommandRejected`
  - `FaultInjected` / `FaultCleared`
  - `SupervisorChanged`
  - `RunStarted` / `RunEnded`
- El **dominio** genera `PieceStamped.waitMs` como el tiempo entre la detección en la estación y el inicio del estampado.

---

## 5. Contratos (primero el contrato, luego el código)

El gemelo es el primer proveedor, así que en T1 crea la parte del contrato que le corresponde en `contracts/` (raíz del monorepo):

```
contracts/
├── schemas/   envelope.schema.json, telemetry.schema.json, event.schema.json, command.schema.json,
│              command-result.schema.json, heartbeat.schema.json, scenario.schema.json, fault.schema.json
├── examples/  un .json válido por cada mensaje (los usan las pruebas y el stub)
├── openapi.yaml    (OpenAPI 3.1, rutas /api/twin/*)
└── asyncapi.yaml   (AsyncAPI 3.0, canal /ws/telemetry)
```
Todo cambio de contrato se hace primero en `contracts/` y en sus pruebas. `schemaVersion` empieza en `"1.0"`.

### 5.1 REST (puerto interno 8080; rutas con prefijo, tal como llegan desde el gateway)

| Método y ruta | Cuerpo / parámetros | Respuestas |
|---|---|---|
| `GET /api/twin/state` | — | 200 con el snapshot (misma forma que la telemetría, sin `type`) |
| `GET /api/twin/limits` | — | 200 con los límites de comandos y la ventana de proceso (el agente los usa para auto-limitarse) |
| `POST /api/twin/commands` | `{commandId, type, params, issuedBy, reason?}` | 202 `APPLIED`; 409 `REJECTED` con `rule`; 400 si el JSON o el esquema son inválidos (ProblemDetail, RFC 9457); 503 si se agota `apply-timeout-ms` |
| `POST /api/twin/commands/heartbeat` | `{agentId, seq, sentAt}` | 204 |
| `GET /api/twin/sim/scenarios` | — | 200 `[ {id, description, durationSimS} ]` |
| `POST /api/twin/sim/scenario` | `{scenarioId, seed?, speedFactor (1–60), controlMode?}` | 201 `{runId}`; 404 si el escenario no existe; 400 |
| `POST /api/twin/sim/control` | `{action: PAUSE\|RESUME\|STOP\|SET_SPEED, speedFactor?}` | 200 con el estado de simulación |
| `GET /api/twin/sim/faults` | — | 200 `{catalog:[{type, params, defaults}], active:[…]}` |
| `POST /api/twin/sim/faults` | `{type, params?, durationS?}` | 201 `{faultId}`; 400 |
| `DELETE /api/twin/sim/faults/{faultId}` | — | 204; 404 |
| `GET /actuator/health` | — | 200 (healthcheck de Compose) |

**Idempotencia**: un `commandId` repetido devuelve la respuesta original con la cabecera `Idempotent-Replay: true` y no se vuelve a aplicar (LRU de `idempotency-capacity` entradas).

Ejemplo:
```json
POST /api/twin/commands
{ "commandId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "type": "SET_PID",
  "params": { "kp": 9.5, "ki": 0.11, "kd": 0.0 },
  "issuedBy": "agent", "reason": "Ganancia térmica estimada cayó 18 %" }

202 { "commandId": "7c9e…", "status": "APPLIED", "appliedAtSimMs": 1234500 }
409 { "commandId": "7c9e…", "status": "REJECTED", "rule": "KP_MAX_STEP_20PCT",
      "detail": "kp 9.5 → 12.0 excede el 20 %" }
```

### 5.2 WebSocket `/ws/telemetry`

- Canal de solo lectura: el servidor ignora lo que envíe el cliente.
- Parámetro `?groundTruth=true` incluye el bloque `groundTruth` (para dashboard y evaluación). **El agente se conecta sin él.**
- Al conectar, el servidor envía `hello` (`schemaVersion`, `runId`, `speedFactor` y el snapshot actual).
- Todos los mensajes comparten un sobre: `type`, `schemaVersion`, `seq` (monótono por corrida, compartido entre telemetría y eventos para detectar huecos), `runId`, `ts` (pared, ISO-8601), `simTs` (inicio de la corrida + tiempo simulado, ISO-8601) y `simTimeMs`.

```json
{ "type": "telemetry", "schemaVersion": "1.0", "seq": 18234, "runId": "b1f0…",
  "ts": "2026-11-21T21:30:00.000Z", "simTs": "2026-11-21T21:50:34.500Z", "simTimeMs": 1234500,
  "speedFactor": 10, "state": "STAMPING",
  "thermal":  { "tempC": 61.8, "setpointC": 62.5, "band": "OK", "heaterDuty": 0.23, "ssr": true,
                "tcMillivolts": 1.55, "ambientC": 24.1 },
  "conveyor": { "running": false, "pwmDuty": 0.70, "speedMps": 0.0, "queueLength": 0 },
  "sensors":  { "inductive": 0, "photoelectric": 1 },
  "valves":   { "discard": "RETRACTED", "stamp": "EXTENDED", "stampStrokeMs": 350 },
  "counters": { "metalDiscarded": 37, "plasticStamped": 412, "metalEscaped": 0, "piecesArrived": 452 },
  "energy":   { "heaterWh": 512.3, "motorWh": 40.1 },
  "control":  { "mode": "PID", "kp": 10.0, "ki": 0.1, "kd": 0.0, "supervisor": "AGENT", "agentHeartbeatAgeMs": 900 },
  "safety":   { "estop": false, "interlock": null, "activeAlarms": [] },
  "groundTruth": { "platenTempC": 61.9, "ambientC": 24.0, "heaterEfficiency": 0.95,
                   "activeFaults": [], "stampedOutOfWindow": 0 } }
```
```json
{ "type": "event", "schemaVersion": "1.0", "seq": 18235, "runId": "b1f0…", "ts": "…", "simTs": "…",
  "simTimeMs": 1234600, "eventType": "PIECE_STAMPED",
  "data": { "pieceId": "P-000412", "tempC": 61.8, "waitMs": 0, "stampStrokeMs": 350 } }
```
- Cambio respecto del borrador anterior: `control.mode` es `LEGACY|PID`, y la supervisión del agente va aparte en `control.supervisor` (`AGENT|NONE`).
- Redondeo en mensajes: temperaturas a 0,01; ciclos útiles a 0,001; energía a 0,1.

---

## 6. Configuración (`application.yml`)

Toda clave `twin.*` se puede sobrescribir por variable de entorno, por ejemplo `TWIN_AUTOSTART_SCENARIO` o `TWIN_ENGINE_SPEEDFACTOR`. `TwinProperties` es un record anidado con `@ConfigurationProperties("twin")` y `@Validated`.

```yaml
server: { port: 8080, shutdown: graceful }
spring:
  application.name: twin
  threads.virtual.enabled: true
management:
  endpoints.web.exposure.include: health,info,metrics
  endpoint.health.probes.enabled: true
twin:
  engine:      { tick-ms: 100, wall-cycle-ms: 100, speed-factor: 1, max-speed-factor: 60 }
  telemetry:   { period-sim-ms: 1000, allowed-origins: "*", outbound-queue-capacity: 10000 }
  autostart:   { scenario: nominal, seed: 42, speed-factor: 1 }        # scenario vacío = no arrancar
  process:     { window-min-c: 50.0, window-max-c: 75.0, band-hysteresis-c: 0.5, stable-ms: 2000,
                 default-setpoint-c: 62.5, heatup-timeout-ms: 900000, thermal-wait-alarm-ms: 120000 }
  thermal:     { heater-power-w: 1500, heater-efficiency: 0.95, capacitance-j-per-k: 8000,
                 rth-k-per-w: 0.115, transport-delay-ms: 5000, sensor-tau-ms: 4000,
                 initial-temp-c: 25.0, piece-heat-j: 2500 }
  sensing:     { seebeck-mv-per-c: 0.041, amplifier-gain: 250, adc-bits: 12, adc-vref-v: 3.3,
                 input-offset-mv: 0.0, noise-sigma-c: 0.3, cold-junction: AMBIENT_SENSOR,
                 fixed-cold-junction-c: 25.0, ambient-noise-sigma-c: 0.1, open-tc-counts-threshold: 4090 }
  control:
    default-mode: PID
    pid:       { kp: 10.0, ki: 0.1, kd: 0.0, sample-ms: 1000, derivative-filter-n: 10 }
    ssr-window-ms: 2000
    legacy:    { sample-ms: 1010, threshold-c: 50.0 }
  conveyor:    { length-m: 2.0, inductive-at-m: 0.60, station-at-m: 1.50, exit-qa-at-m: 1.95,
                 piece-length-m: 0.06, min-spacing-m: 0.15, nominal-speed-mps: 0.25, accel-mps2: 2.0,
                 default-pwm: 0.7, motor-rated-w: 100, discard-face-from-m: -0.01, discard-face-to-m: 0.07,
                 miss-prob-base: 0.003, miss-prob-speed-gain: 0.05, miss-ref-speed-mps: 0.15 }
  pneumatics:  { stroke-ms: 350, stamp-dwell-ms: 1000, stroke-timeout-ms: 1500 }
  safety:      { over-temp-trip-c: 90.0, watchdog-timeout-ms: 10000 }
  commands:
    setpoint-max-step-c: 5.0
    pid-max-step-fraction: 0.20
    kp: { min: 0.5, max: 60.0 }
    ki: { min: 0.0, max: 2.0 }
    kd: { min: 0.0, max: 120.0 }
    belt-pwm: { min: 0.4, max: 1.0, max-step: 0.2 }
    apply-timeout-ms: 2000
    idempotency-capacity: 1000
```
Los valores térmicos son supuestos calibrables: dan un calentamiento de unos 4 minutos y un ciclo útil de equilibrio cercano al 23 %. El anexo A trae los valores esperados que se derivan de ellos.

---

## 7. Patrones de diseño

| Patrón | Dónde | Por qué |
|---|---|---|
| Hexagonal (puertos y adaptadores) | Todo el servicio; `PlantPort`, puertos `in`/`out` | Aislar el dominio de Spring y del transporte; permitir sustituir la planta o el canal |
| Scan cycle (PLC) | `StampingMachine.scan` | Orden determinista leer → decidir → escribir → integrar, como el firmware real |
| State | `sequence/*StateHandler` | Cada estado encapsula sus salidas y transiciones; agregar lógica a un estado no toca los demás |
| Strategy | `TemperatureController` (LEGACY/PID); `ArrivalGenerator` | Cambiar la estrategia térmica en caliente para la comparación A/B |
| Chain of Responsibility | `InterlockChain` | Prioridad explícita de la seguridad; el primer disparo gana |
| Command | `MachineCommand` + `CommandInbox` | Comandos como datos: encolables, auditables, idempotentes y aplicados por un único hilo |
| Specification / Rule | `CommandRule` + `CommandValidator` | Cada regla de seguridad es una clase pequeña con su código de rechazo |
| Pipeline | `ThermocoupleChain` con `SignalStage` | Cada etapa física del acondicionamiento se prueba aislada |
| Parameter Object | `PlantModifiers`, records `*Parameters` | Las fallas modifican perillas, no el código de la física |
| Factory + Registry | `FaultFactory`, `ScenarioRepository` | Crear fallas y escenarios por tipo o identificador sin `switch` dispersos |
| Observer / Publish-Subscribe | `DomainEvent` → `TelemetryPublisher` | El dominio emite hechos; los adaptadores deciden cómo difundirlos |
| Active Object / Single Writer | `EngineScheduler` + `CommandInbox` | Sin locks en el dominio: un solo hilo lo muta |
| Snapshot inmutable | `MachineSnapshot` en `AtomicReference` | Lecturas concurrentes sin bloquear |
| Idempotency Key | `IdempotencyStore` | Reintentos seguros del agente |
| Anti-Corruption Layer | DTO + mappers en `adapter` | El JSON del contrato no contamina el dominio |
| Null Object | `NoFault`, `PlantModifiers.neutral()` | Evitar `null` y condicionales |

---

## 8. Principios SOLID aplicados

**S: responsabilidad única.** Cada clase tiene una sola razón para cambiar:
- `ThermalPlant` solo integra el balance de energía.
- `ThermocoupleChain` solo mide.
- `Sequencer` solo decide la secuencia.
- `InterlockChain` solo decide la seguridad.
- `CommandValidator` solo aplica reglas.
- `TelemetryAssembler` arma el mensaje y `WebSocketTelemetryPublisher` solo lo transporta.
- `SimulationEngine` avanza el tiempo y `EngineScheduler` maneja el hilo.

Cambiar el formato JSON no toca el dominio; cambiar la física no toca la API.

**O: abierto/cerrado.**
- Para una falla nueva basta una clase que implemente `Fault` y su registro en `FaultFactory`.
- Para una regla nueva basta un bean `CommandRule` (el validador recibe `List<CommandRule>`).
- Para un controlador nuevo basta implementar `TemperatureController`.
- Excepción deliberada: `MachineCommand` y `DomainEvent` son *sealed*, porque forman parte del contrato versionado. Así el compilador obliga a manejar cada caso (`switch` exhaustivo) y un tipo nuevo exige un cambio de contrato.

**L: sustitución de Liskov.** Las pruebas de contrato abstractas aseguran que cualquier implementación sea intercambiable:
- `TemperatureControllerContract` para LEGACY y PID: salida en [0, 100], determinismo y `reset` limpio.
- `PlantPortContract` para `SimulatedPlant` y `FakePlant`: entradas coherentes con las salidas escritas y avance de tiempo monótono.
- `FaultContract`: neutral antes de empezar y después de vencer.

**I: segregación de interfaces.**
- Cinco puertos de entrada pequeños en lugar de un servicio gordo. `QueryStateUseCase` (lectura) está separado de `ApplyCommandUseCase` (escritura), según CQS.
- `TelemetryPublisher` solo tiene `publishTelemetry` y `publishEvent`.
- `PlantPort` solo tiene `readInputs`, `writeOutputs` y `advance`.

**D: inversión de dependencias.**
- `domain` declara `PlantPort`; `application` declara `TelemetryPublisher`, `ScenarioRepository` y `WallClock`; los adaptadores los implementan.
- Solo `config/` instancia y conecta, con `@Bean` en lugar de `@Component` en dominio y aplicación.
- ArchUnit hace cumplir que `domain` y `application` no importen Spring, Jackson ni Jakarta.

**Complementarios**
- Inmutabilidad: records para valores, snapshots y mensajes.
- Fail-safe: ante cualquier duda, salidas seguras.
- Sin `null` en el dominio: `Optional` o Null Object.
- Unidades en los nombres: `tempC`, `powerW`, `timeMs`, `speedMps`.

---

## 9. Estrategia de pruebas

### 9.1 Niveles

| Nivel | Qué | Herramientas |
|---|---|---|
| Unitarias del dominio | Física, medición, banda, neumática, controladores, estados, interlocks, reglas y fallas. Sin Spring y sin hilos | JUnit, AssertJ, `@ParameterizedTest` |
| Contrato de interfaz | Clases abstractas `*Contract` que heredan las implementaciones (LSP) | JUnit |
| Aplicación | `SimulationEngine` síncrono, bandeja de comandos, idempotencia, corridas y autoarranque, con `FakeWallClock` y `RecordingPublisher` | JUnit |
| Integración web | `@SpringBootTest(webEnvironment = RANDOM_PORT)`: REST con `RestClient`/MockMvc y WebSocket con `StandardWebSocketClient` | Spring test, Awaitility |
| Contrato de mensajes | Todo mensaje saliente (telemetría, eventos, `hello`, respuestas REST) y todo ejemplo de `contracts/examples` validados contra JSON Schema | json-schema-validator |
| Determinismo (golden) | Escenario `nominal`, semilla 42, 30 min simulados en modo MAX: el SHA-256 de los mensajes canónicos (sin `ts`, `simTs` ni `runId`) coincide con `src/test/resources/golden/nominal-42.sha256`. Se regenera solo con `-Dgolden.update=true` | JUnit |
| Arquitectura | Reglas ArchUnit (9.3) | ArchUnit |
| Rendimiento | 1 h simulada en modo MAX en menos de 2 s; tick p99 < 0,2 ms (prueba etiquetada `@Tag("perf")`) | JUnit |

Para que las pruebas lean `../contracts`, Surefire recibe la propiedad de sistema `contracts.dir=${project.basedir}/../contracts`.

### 9.2 Casos obligatorios (mínimo)

- **Tabla de verdad**: las 16 filas del ITO 2 en `src/test/resources/legacy-truth-table.csv`, verificadas contra `LegacyLogic`.
- **Física**: los valores del anexo A, con su tolerancia.
- **PID**:
  - salida acotada;
  - anti-windup en el calentamiento (sobrepico < 2 °C);
  - cambio de ganancias sin salto (|Δu| < 0,5 % en el tick del cambio);
  - paso LEGACY → PID sin salto.
- **SSR**: la duty media en 100 ventanas es igual a la duty pedida ± 0,1 %.
- **Lazo cerrado** (planta real, sin fallas, 1 h simulada): en PID, 100 % del tiempo en ventana después del calentamiento y |media − setpoint| < 0,5 °C; en LEGACY, menor porcentaje en ventana que en PID, porque oscila alrededor de 50 °C.
- **Estados**: cada transición de la tabla 4.6, el `STOP` pendiente y que no haya disparos con la banda detenida.
- **Seguridad**:
  - paro de emergencia → salidas seguras en el mismo scan;
  - prioridad de la cadena (con termocupla abierta y sobretemperatura a la vez gana `THERMOCOUPLE_OPEN`);
  - `RESET` rechazado sin reconocimiento o con la causa activa.
- **Watchdog**: con `FakeWallClock`, a los 10 s sin latido aplica parámetros seguros y levanta `WATCHDOG_TIMEOUT` una sola vez; al volver el latido, `supervisor = AGENT`.
- **Reglas**: cada código de rechazo con al menos un caso válido y uno inválido.
- **Comandos**: idempotencia (mismo `commandId` → una sola aplicación); 409 con `rule`; 503 si el motor no responde.
- **Fallas**: cada una produce su efecto del anexo A. `THERMOCOUPLE_OPEN` lleva a `FAULT` por sí sola; `PNEUMATIC_LEAK` con factor 5 → `PNEUMATIC_TIMEOUT`.
- **Escenarios**: los 9 archivos cargan y validan contra `scenario.schema.json`.
- **WebSocket**: `hello` al conectar; `groundTruth` solo con el parámetro; un cliente lento no frena el motor.

### 9.3 Reglas ArchUnit

1. `..domain..` no depende de `org.springframework..`, `tools.jackson..`, `com.fasterxml..`, `jakarta..`, `..application..`, `..adapter..` ni `..config..`.
2. `..application..` no depende de `org.springframework..`, `..adapter..` ni `..config..`.
3. `..adapter.in..` y `..adapter.out..` no dependen entre sí.
4. `..domain.control..` no depende de `..domain.plant..`, y viceversa.
5. No hay ciclos entre subpaquetes de `domain` (`slices().matching("..domain.(*)..")`).
6. En `..domain..` está prohibido llamar a `System.currentTimeMillis`, `System.nanoTime`, `Instant.now`, `LocalDateTime.now`, `Math.random` y `Thread.sleep`, y usar `java.util.Random` o `ThreadLocalRandom`.
7. Solo `..adapter.in.rest..` usa `@RestController`; solo `..config..` usa `@Configuration`/`@Bean`.
8. Los records de `..adapter..dto..` y `..adapter..message..` no se usan fuera de `..adapter..`.

---

## 10. Flujo de desarrollo

- **Ramas**: una por iteración (`feat/twin-T3-thermal`), corta y fusionada a `main` por PR con CI en verde.
- **Commits**: Conventional Commits (`feat(twin): …`, `test(twin): …`, `refactor(twin): …`), pequeños y con pruebas.
- **TDD por componente**: rojo → verde → refactor. La prueba que fija el comportamiento (anexo A, tabla 4.6, reglas 4.9) se escribe antes que la clase.
- **Compuerta de calidad** (`./mvnw verify`): compilación sin advertencias nuevas, Spotless, pruebas, ArchUnit y JaCoCo con umbral. La prueba `perf` corre con `./mvnw verify -Pperf`.
- **CI** (`.github/workflows/twin.yml`, filtrado por rutas `twin-java/**` y `contracts/**`): JDK 21 Temurin con caché de Maven, `./mvnw -B verify` y `docker build twin-java`.
- **Comandos de trabajo**

```bash
./mvnw spotless:apply           # formatear
./mvnw verify                   # compuerta completa
./mvnw spring-boot:run          # correr local (puerto 8080)
docker build -t twin-java .     # imagen
docker compose up --build twin  # desde la raíz del monorepo
```

- **Cierre de iteración**: compuerta en verde, README del servicio actualizado si cambió el uso, y resumen al usuario con lo hecho, lo pendiente y los riesgos. Luego se espera aprobación.

---

## 11. Iteraciones

Orden de adentro hacia afuera (dominio → aplicación → adaptadores), con un stub temprano para que el agente y el dashboard puedan avanzar en paralelo.

### T0: Andamiaje y reglas
- [ ] Generar el proyecto en start.spring.io (sección 2) dentro de `twin-java/`, con el wrapper de Maven.
- [ ] Configurar en el `pom.xml` Spotless, JaCoCo con umbrales, Surefire con `contracts.dir`, el perfil `perf` y `finalName=twin-java`.
- [ ] Crear el esqueleto de paquetes (3.3) con `package-info.java` (`@NullMarked`).
- [ ] Escribir `ArchitectureTest` con las 8 reglas (usar `allowEmptyShould(true)` mientras haya paquetes vacíos).
- [ ] Crear `application.yml` (sección 6) con `TwinProperties` validado y una prueba de que el contexto carga.
- [ ] Escribir el `Dockerfile` (sección 12), `.dockerignore`, el workflow de CI y el `README.md` del servicio.

**Hecho cuando:** `./mvnw verify` pasa, `docker build` funciona y `/actuator/health` responde UP.

### T1: Contratos y stub
- [ ] Escribir `contracts/` (sección 5): esquemas, ejemplos, `openapi.yaml` y `asyncapi.yaml`.
- [ ] Crear `ContractExamplesTest`, que valida todos los ejemplos contra sus esquemas.
- [ ] Crear el perfil `stub`: un publicador temporal que reenvía `contracts/examples/telemetry*.json` por `/ws/telemetry` a 1 Hz y responde 202 a los comandos. Se elimina en T9.

**Hecho cuando:** los ejemplos son válidos y con `--spring.profiles.active=stub` un cliente WebSocket recibe mensajes que cumplen el contrato.

### T2: Tiempo, unidades y aleatoriedad
- [ ] Crear `SimTime`, `SimDuration` y los value objects de `shared`, con validación en el constructor.
- [ ] Crear `RandomStreams` (flujos con nombre, Box–Muller con `StrictMath`).
- [ ] Crear `ProcessInputs`, `ProcessOutputs` y `PlantPort` (`io`).

**Hecho cuando:** existen pruebas de que (a) la misma semilla da la misma secuencia, (b) los flujos son independientes y (c) la gaussiana tiene media ≈ 0 y σ ≈ 1 en 10⁵ muestras (±1 %).

### T3: Planta térmica y medición
- [ ] Crear `ThermalPlant`, `DelayLine` y `FirstOrderLag`.
- [ ] Crear `ThermocoupleChain` con sus 6 etapas, `Adc` y `AmbientSensor`.

**Hecho cuando:** los valores térmicos y de medición del anexo A pasan con su tolerancia.

### T4: Banda, sensores, neumática y planta simulada
- [ ] Crear `Motor`, `Conveyor`, `Piece`, `ArrivalGenerator`, los sensores inductivo y fotoeléctrico, y `ExitInspection`.
- [ ] Crear `PneumaticCylinder`.
- [ ] Crear `SimulatedPlant` (implementa `PlantPort`) y `PlantModifiers`.
- [ ] Crear la prueba de contrato `PlantPortContract` y `FakePlant` (en `test`).

**Hecho cuando:** pasan la velocidad, `p_miss`, los tiempos de carrera y el descarte del anexo A; las piezas no se superponen; y la cola de entrada crece con la banda detenida.

### T5: Control térmico
- [ ] Crear `LegacyLogic` con la prueba de la tabla de verdad.
- [ ] Crear `LegacyOnOffController`, `PidController` y `TimeProportionalSsr`.
- [ ] Crear `TemperatureControllerContract`.

**Hecho cuando:** pasan las pruebas de PID y SSR de 9.2 y las de lazo cerrado contra `ThermalPlant` (sin banda).

### T6: Secuencia, seguridad, alarmas, comandos y máquina
- [ ] Crear los `StateHandler` (8), `Sequencer`, `InterlockChain` (4), `Watchdog`, `AlarmRegistry`, `MachineCommand`, las `CommandRule` (tabla 4.9) y `CommandValidator`.
- [ ] Crear `ControlLogic`, `StampingMachine.scan()`, `Counters`, `EnergyMeter` y los `DomainEvent`.

**Hecho cuando:** pasan las transiciones de 4.6, las pruebas de seguridad, el watchdog, todas las reglas y una corrida de 1 h sin fallas con piezas que fluyen, contadores coherentes y cero violaciones de la ventana medida al estampar.

### T7: Fallas y escenarios
- [ ] Crear las 8 implementaciones de `Fault`, `FaultFactory` y `FaultInjector`.
- [ ] Crear el modelo `Scenario` y los 9 archivos JSON.
- [ ] Crear `ScenarioRepository` (puerto) y `ClasspathScenarioRepository`.
- [ ] Crear la prueba golden.

**Hecho cuando:** cada falla produce su efecto del anexo A, los escenarios validan y la prueba golden es estable en tres ejecuciones seguidas.

### T8: Motor de simulación y casos de uso
- [ ] Crear `SimulationEngine` (síncrono), `EngineScheduler`, `CommandInbox`, `PendingCommand`, `IdempotencyStore`, `RunContext` y el autoarranque.
- [ ] Implementar los cinco casos de uso, `TelemetryAssembler` y el snapshot en `AtomicReference`.

**Hecho cuando:**
- con ×10, en 1 s de pared avanzan 10 s simulados (±2 %);
- pausa, reanudación y `SET_SPEED` funcionan;
- un comando se aplica en ≤ 1 ciclo de pared;
- un `commandId` duplicado se aplica una sola vez;
- la prueba `perf` pasa.

### T9: Adaptadores REST y WebSocket
- [ ] Crear los controladores, DTO, mappers y `RestExceptionHandler` (ProblemDetail).
- [ ] Crear `TelemetryWebSocketHandler`, `SessionRegistry`, `WebSocketTelemetryPublisher` (cola acotada e hilo `ws-broadcaster`) y `WebSocketConfig`.
- [ ] Hacer el cableado completo en `config/`.
- [ ] Eliminar el perfil `stub`.

**Hecho cuando:**
- pasan las pruebas de integración REST y WebSocket;
- todo mensaje saliente valida contra JSON Schema;
- la prueba de deriva confirma que cada ruta y método de `contracts/openapi.yaml` existe en `/v3/api-docs` y viceversa.

### T10: Endurecimiento e integración en Compose
- [ ] Crear las métricas Micrometer `twin.tick.duration`, `twin.ws.sessions` y `twin.telemetry.dropped`.
- [ ] Configurar logs con `runId` en el MDC y el cierre ordenado: `RUN_ENDED` y cierre de sesiones WebSocket.
- [ ] Ajustar el servicio `twin` en `docker-compose.yml` (sección 12).
- [ ] Completar el README del servicio: arranque, endpoints, escenarios y fallas, con ejemplos `curl` a través del gateway.

**Hecho cuando:** `docker compose up --build` levanta el gemelo sano con el autoarranque `nominal` y un cliente WebSocket en `ws://localhost:8080/ws/telemetry` (a través del gateway) recibe telemetría válida.

---

## 12. Contenedor

```dockerfile
# twin-java/Dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/twin-java.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
Servicio en `docker-compose.yml` (sin puertos publicados). Se baja a 512 MB para dejar memoria a Ollama:
```yaml
  twin:
    build: ./twin-java
    environment:
      JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75
      TWIN_AUTOSTART_SCENARIO: nominal
      TWIN_AUTOSTART_SPEEDFACTOR: 1
    mem_limit: 512m
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 10s
      retries: 12
```

---

## 13. Reglas para Claude Code

1. **Dominio puro**: nada de Spring, Jackson ni Jakarta en `domain`, ni en `application`. Las anotaciones de Spring solo van en `adapter` y `config`.
2. **Nada de reloj ni azar directos** en el dominio: usa `SimTime`, `WallClock` y `RandomStreams`. En el dominio, `StrictMath` en lugar de `Math` para funciones trascendentes.
3. **Un solo escritor**: solo el hilo `sim-engine` muta el dominio. No agregues `synchronized` ni locks al dominio.
4. **Records** para value objects, DTO, mensajes y snapshots. Sin Lombok y sin setters.
5. **Unidades en los nombres** y ningún número mágico: todo parámetro sale de `TwinProperties`.
6. **Jackson 3**: importa `tools.jackson.databind.*` (anotaciones `com.fasterxml.jackson.annotation.*`). En pruebas usa `@MockitoBean`.
7. **Contrato primero**: si cambia un mensaje o un endpoint, actualiza `contracts/` y sus pruebas en el mismo cambio y sube `schemaVersion` si rompe compatibilidad.
8. **No cambies los valores físicos por defecto** ni regeneres el golden sin avisar: rompen la reproducibilidad de las demos.
9. **Seguridad ante todo**: ninguna ruta de código puede encender el SSR o mover la banda en `EMERGENCY_STOP` o `FAULT`. Cualquier rama nueva de salidas necesita su prueba.
10. **Idioma**: código, identificadores y nombres de pruebas en inglés; Javadoc breve en español donde explique física o reglas del proceso; mensajes de `detail` en español.
11. **Alcance**: no implementes persistencia, autenticación, lógica del agente ni interfaz gráfica.
12. **Al terminar cada iteración**, detente y espera aprobación.

---

## 14. Definición de terminado del servicio

- [ ] T0–T10 completas, con `./mvnw verify` y `./mvnw verify -Pperf` en verde.
- [ ] Contratos publicados en `contracts/` y validados por pruebas.
- [ ] Golden estable; cobertura de `domain` ≥ 85 %.
- [ ] `docker compose up --build` deja el gemelo sano, con telemetría válida por el gateway.
- [ ] README del servicio con arranque, endpoints, escenarios y fallas.

---

## Anexo A. Valores de verificación (con los parámetros por defecto)

| Caso | Condición | Valor esperado | Tolerancia |
|---|---|---|---|
| Constante de tiempo | τ = R_th·C | 920 s | exacto |
| Equilibrio al 100 % | T_amb 25 °C | 188,9 °C (ΔT = 163,875 °C) | ±0,5 °C |
| Escalón al 100 % | desde 25 °C, t = 925 s (τ + retardo de 5 s) | 128,59 °C (temperatura real) | ±0,5 °C |
| Llegar a 50 °C | 100 %, temperatura real | 157,3 s | ±1 s |
| Llegar a 62,5 °C | 100 %, temperatura real | 244,1 s | ±1 s |
| Ciclo útil para sostener 62,5 °C | equilibrio, sin piezas | 22,88 % | ±0,1 % |
| Caída por pieza estampada | 2500 J / 8000 J/K | 0,31 °C | ±0,02 °C |
| Resolución de la medición | 1 LSB | 0,0786 °C | exacto |
| Lectura a 62,5 °C | T_cj 25 °C, sin ruido ni offset | 477 counts → 62,50 °C | ±1 count |
| Lectura con offset 0,01 mV | ídem | 480 counts → 62,74 °C | ±1 count |
| Termocupla abierta | ADC saturado | 4095 counts → 346,95 °C → `THERMOCOUPLE_OPEN` | exacto |
| Velocidad de banda | pwm 0,7 | 0,175 m/s | ±1 % |
| Velocidad con `MOTOR_WEAR` 1,3 | pwm 0,7 | 0,1346 m/s | ±1 % |
| `p_miss` | v = 0,175 / 0,25 / 0,10 m/s | 0,0113 / 0,0363 / 0,0030 | exacto |
| Carrera neumática | 350 ms; fuga ×1,5; fuga ×5 | EXTENDED en el tick 4; en el tick 6; `PNEUMATIC_TIMEOUT` a los 1.500 ms | exacto |
| Watchdog | último latido en t₀ (pared) | parámetros seguros y alarma en t₀ + 10 s | ±1 ciclo |
| Sobrepico de calentamiento en PID | kp 10, ki 0,1 | < 2 °C (referencia simulada: 1,3 °C) | — |
| A/B de referencia | 1 h, sin fallas | % en ventana: PID ≈ 100 %, LEGACY ≈ 70–75 % (oscila en 50 °C) | relación PID > LEGACY |
