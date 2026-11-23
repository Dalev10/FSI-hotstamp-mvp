# Simex · Prototipo local de estampación en caliente

Simex supervisa el gemelo digital de una estampadora. El gemelo Java simula la resistencia, la banda, los sensores y los pistones, y ejecuta el control y las protecciones. Una aplicación Python ejecuta el agente y sirve el tablero. Desde el navegador se puede iniciar una corrida, elegir el modo de supervisión y seguir una decisión desde la evidencia hasta su resultado.

## Arrancar en este portátil

Se probó en macOS con Apple Silicon, Java 21 y Python 3.9.6. Los dos programas escuchan solo en este equipo. Usa dos terminales abiertas en la raíz del repositorio.

**Terminal 1 · Gemelo Java**

```bash
cd twin-java
./mvnw -Dspring-boot.run.arguments=--server.address=127.0.0.1 spring-boot:run
```

Si `java -version` no encuentra Java 21, este portátil tiene una copia local preparada durante el ensayo. Ejecuta esto desde la raíz en lugar del comando anterior.

```bash
export JAVA_HOME="$PWD/.simex-tools/zulu-21.jdk/Contents/Home"
export MAVEN_USER_HOME="$PWD/.simex-tools/maven"
cd twin-java
./mvnw -o -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" \
  -Dspring-boot.run.arguments=--server.address=127.0.0.1 spring-boot:run
```

En otro equipo, instala un **JDK** (kit de desarrollo de Java) 21. El primer arranque con Maven necesita acceso a internet para descargar sus dependencias.

**Terminal 2 · Agente y tablero**

```bash
# Solo la primera vez, si todavía no existe el entorno con sus dependencias
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt

# En cada arranque, desde la raíz
.venv/bin/python -m uvicorn simex.app:app --host 127.0.0.1 --port 8000
```

Abre [el tablero local](http://127.0.0.1:8000/) y comprueba que aparece **Telemetría en vivo**. El gemelo arranca por defecto con una corrida que el tablero muestra como «Escenario desconocido» porque fue iniciada fuera de Python. Usa **Iniciar escenario** para que el agente supervise una corrida identificada. Para apagar la demo, usa `Ctrl+C` en ambas terminales. Una vez preparadas las dependencias, los dos programas y la página funcionan localmente sin internet.

## Cómo se comunican y qué puertos usan

| Origen y destino | Puerto y protocolo | Qué pasa por esa conexión |
| --- | --- | --- |
| Navegador ↔ Python con FastAPI y Uvicorn | `127.0.0.1:8000`, HTTP (protocolo web) | Python entrega la página. Esta consulta `/api/status` aproximadamente una vez por segundo y envía las solicitudes de escenario, modo y comparación. |
| Gemelo Java → Python | `127.0.0.1:8080/ws/telemetry`, WebSocket (conexión persistente) | Estado inicial mediante `hello`, una muestra por segundo simulado y eventos de máquina, alarmas, piezas y comandos. El agente usa la vista pública. |
| Python → gemelo Java | `127.0.0.1:8080/api/twin/`, REST (peticiones HTTP puntuales) | Lee el estado y los límites, inicia corridas y envía el latido y los ajustes de ganancias autorizados. |

La demo se probó **sin Nginx**, con conexión directa a Java. El gateway Nginx del repositorio sigue disponible en Docker Compose y ocupa el puerto 8080 cuando se usa. En ese caso Python se conecta al gateway y carga la clave desde `TWIN_API_KEY`. El navegador sigue abriendo el puerto 8000. `SIMEX_TWIN_URL` permite cambiar la dirección del gemelo. [Guía detallada del gemelo](twin-java/README.md) · [Configuración de Compose](docker-compose.yml)

## Guion para la sustentación

Usa semilla **42**. Empieza en **×10** para seguir el calentamiento y la perturbación o usa **×30** para abreviar. En los tres escenarios de falla, la perturbación se programa en el minuto 10 simulado. El tablero muestra la consigna, la medición, la ventana de 50–75 °C, los ajustes y las alarmas en la misma línea de tiempo.

| Caso | Qué hacer y qué mostrar |
| --- | --- |
| **Operación normal** | Inicia la corrida y espera la referencia térmica. El agente sigue observando y el registro debe quedar sin ajustes. |
| **Ruido de sensor, en sombra** | Elige **Sombra** e inicia el escenario. Aparece **Propuesta en sombra** con las ganancias anteriores y propuestas. La corrida no envía un ajuste ni latidos. |
| **Ruido de sensor, en automático** | Inicia una corrida nueva en **Automático**. La decisión pasa por la confirmación del gemelo y la verificación. Muestra el ruido, las ganancias aplicadas, la variación del ciclo útil, el error y las muestras en ventana. A ×30 el resultado suele aparecer cerca de 30 s reales después del arranque. |
| **Frente frío** | Elige el escenario y espera **Frente frío · conservar el ajuste**. Compara la caída del ambiente con la temperatura, la consigna y la demanda de calefacción. |
| **Termocupla abierta** | Elige el escenario y espera **Paro confirmado**. Señala la alarma crítica, la resistencia apagada, la banda detenida y los pistones retraídos. El gemelo ejecutó el paro. |
| **Comparación** | Selecciona **Ruido de sensor**, semilla **42**, velocidad **×60** y pulsa **Ejecutar comparación**. Los tres brazos tardan unos tres minutos en total. También puedes elegir la corrida de referencia ya registrada en la misma tabla. |

## Reglas y verificación

El regulador proporcional, integral y derivativo (**PID**) usa `kp`, `ki` y `kd` para responder al error actual, acumulado y a su variación. El ciclo útil indica la fracción de tiempo que se solicita encender la resistencia. El agente calcula una referencia por corrida con dos ventanas operativas y estables de 60 segundos simulados.

- **Ruido.** Estima su dispersión con las diferencias de temperatura entre muestras consecutivas. Dos ventanas sobre tres veces la referencia activan una propuesta. En automático se aplica un solo paso de reducción de `kp` y `ki`, con `kd` fijo. El paso probado fue 16,5 %, calculado para que la ida y la vuelta quepan en el límite publicado.
- **Verificación.** El gemelo responde con el código HTTP 202, emite `COMMAND_APPLIED` con el mismo identificador y publica las ganancias nuevas. El agente espera 60 s simulados y compara dos ventanas de 120 s de operación. Pide una reducción de al menos 5 % en la variación del ciclo útil y controla el error térmico y el porcentaje en ventana. Si hay deterioro comparable, intenta restaurar las ganancias. Si cambia la perturbación, se interrumpe el flujo o no se establece un beneficio, registra **No concluyente**.
- **Frente frío.** Una caída ambiental de al menos 3 °C en dos ventanas activa la regla. Para reportar que se sostiene la consigna, exige error absoluto medio de hasta 2 °C, demanda media menor al 90 % y al menos 95 % de muestras en ventana. La primera versión conserva las ganancias y muestra vigilancia si se exceden esos márgenes.
- **Protecciones y latido.** Python solo escribe con una corrida propia en automático, el PID activo, telemetría reciente, la máquina operativa y sin protección crítica ni otro ajuste pendiente. Un hueco de secuencia o de frescura suspende los ajustes en esa corrida. En automático, el latido sale cada dos segundos reales mientras el flujo esté sano. Si vence, el vigilante de Java restaura parámetros seguros. La explicación del paro usa la causa y las salidas observadas.

Las explicaciones son plantillas en español. La implementación está en [`simex/`](simex/) y los formatos de Java están en [`contracts/`](contracts/). Los umbrales se editan en [`policy.py`](simex/policy.py).

## Resultados y archivos

Consulta el [informe del ensayo](docs/demo/README.md) para ver qué se comprobó en el navegador, los valores medidos, los empates y el trabajo pendiente. El tablero permite descargar el resumen de corridas en **CSV** (valores separados por comas) y las decisiones en **JSONL** (un objeto JSON por línea). Los archivos locales se guardan en `.simex-data/`. El [resumen de referencia](docs/demo/resultados.csv) también aparece en la tabla al arrancar.

Pruebas de los cambios, desde la raíz con el entorno preparado.

```bash
.venv/bin/python -m unittest discover -s tests -v
```
