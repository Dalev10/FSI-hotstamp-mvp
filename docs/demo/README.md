# Simex · Ensayo local del 22 de noviembre de 2026

Se usó el navegador integrado de Codex a 663 y 1366 píxeles de ancho, con FastAPI en el puerto 8000 y Java 21 en el 8080. Las corridas se iniciaron desde el tablero con semilla 42. El agente recibió el flujo público del gemelo Java. Las pruebas se hicieron con conexión directa. [Guía de arranque y arquitectura](../../README.md)

## Casos vistos en el navegador

| Caso | Evidencia observada | Resultado |
| --- | --- | --- |
| Ruido de sensor en sombra, ×10 | A los 704 s simulados, ruido de 0,279 a 1,517 °C. Propuesta `kp` 10 → 8,35 y `ki` 0,1 → 0,0835, con `kd` en 0. | Cero ajustes y cero latidos de esa corrida. |
| Ruido de sensor en automático, ×30 | Diagnóstico a los 704 s. Java respondió HTTP 202 y confirmó las ganancias mediante el evento y la telemetría. A los 885 s, la variación del ciclo útil pasó de 17,38 a 12,73 puntos porcentuales (pp), el error medio de 1,38 a 1,26 °C y el porcentaje en ventana fue 100 % antes y después. | Verificado. Un ajuste aplicado. |
| Frente frío en automático, ×30 | A los 884 s, ambiente medio de 20,75 °C frente a 25,01 °C de referencia. Sello de 62,39 °C frente a 62,5 °C de consigna, error medio de 0,43 °C y ciclo útil medio de 41,75 %, con 100 % de muestras en ventana. | El PID sostuvo la consigna y el agente conservó las ganancias. |
| Termocupla abierta en automático, ×30 | Alarma crítica a los 600 s. A los 601 s se confirmó `FAULT`, resistencia apagada, banda detenida a 0 m/s y ambos pistones retraídos. | Paro ejecutado por Java. Cero ajustes del agente. |
| Operación normal en automático, ×60 | Se estableció la referencia a los 404 s y la corrida duró 3.600 s. Se recibieron todas las muestras. | Cero ajustes en toda la corrida. |
| Pérdida de latido en una corrida activa, ×60 | Tras verificar el ajuste por ruido, se cambió a sombra. El gemelo cambió el supervisor a `NONE` y la telemetría mostró `kp` de 8,35 a 10 y `ki` de 0,0835 a 0,1. | Restauración confirmada, con consigna de 62,5 °C y velocidad solicitada del 70 %. |

Las primeras cuatro corridas se reemplazaron tras observar el resultado. Esas corridas parciales son **inconclusas como corridas completas** y no aportan indicadores a la comparación. Se comprobó que recargar la página conserva la corrida y su decisión. Al parar y reiniciar Java, el tablero avisó de la desconexión, deshabilitó el inicio de corridas y recuperó la telemetría sin recargar. Las decisiones y sus comprobaciones están registradas con los identificadores de corrida y, donde aplica, de comando. [Registro de decisiones del ensayo](decisiones.jsonl)

## Comparación con y sin agente

Se completaron tres corridas de `ruido-sensor`, semilla 42, **×60** y 3.600 s simulados por brazo. Cada brazo recibió 3.600 muestras, de las cuales 3.436 fueron operativas. Hubo cero muestras faltantes y cero discontinuidades de secuencia. El control heredado y el PID sin agente corrieron con el latido desactivado. [Datos de las tres corridas](resultados.csv)

| Indicador | Control heredado | PID sin agente | PID con agente |
| --- | ---: | ---: | ---: |
| Piezas estampadas por hora simulada | 186 | 295 | 295 |
| Muestras operativas medidas entre 50 y 75 °C | 73,778 % | 100 % | 100 % |
| Energía total (kilovatios hora) | 0,4366 | 0,6913 | 0,6910 |
| Espera media por pieza estampada (s) | 13,93 | 0,10 | 0,10 |
| Metales escapados | 0 | 1 | 1 |
| Error absoluto medio de temperatura desde el minuto 15 (°C) | 11,4768 | 1,2307 | 1,2328 |
| Variación del ciclo útil desde el minuto 15 (pp) | 43,0143 | 15,8995 | 13,5411 |
| Ajustes del agente | 0 | 0 | 1 |

El agente diagnosticó a los 704 s (104 s después de la inyección), el evento de aplicación llegó a los 708,1 s y se verificó el resultado a los 888 s. En el mismo tramo desde el minuto 15, la variación del ciclo útil fue **14,8 % menor** con el agente. Los brazos PID empataron en producción, muestras en ventana, espera y escapes. La diferencia de energía fue de 0,0003 kilovatios hora. La evidencia de mejora en energía o error térmico queda **inconclusa** con una sola semilla. El control heredado gastó menos energía y tuvo una fracción considerablemente menor de mediciones en ventana.

El cálculo de producción y energía incluye toda la corrida, incluso calentamiento y paradas. El porcentaje en ventana usa solo transporte, descarte, espera térmica y estampado. La espera media procede de los eventos de piezas estampadas. El error es la media de la distancia medida a 62,5 °C y la variación es la desviación estándar del ciclo útil, ambos sobre las muestras operativas desde 900 s. El error usa la lectura del sensor público, sin estimar la temperatura interna real del sello.

## Pruebas automatizadas y pendientes

- Se ejecutaron las 421 pruebas del gemelo y las 8 pruebas de supervisión. La reversión por deterioro se probó con telemetría controlada y una confirmación simulada de la interfaz de Java.
- Queda pendiente probar la reversión por deterioro inducido en el gemelo, repetir la comparación con más semillas y medir el error de la temperatura interna simulada con un lector de evaluación separado.
- Ollama, la compensación de la banda y la comprobación con Docker Compose y Nginx quedan como ampliaciones. Las pruebas de esta entrega se limitan a la simulación académica.
