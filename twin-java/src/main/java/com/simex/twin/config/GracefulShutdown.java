package com.simex.twin.config;

import com.simex.twin.adapter.out.websocket.WebSocketTelemetryPublisher;
import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.application.port.in.SimulationControlUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.socket.CloseStatus;

/**
 * Cierre ordenado. Se detiene antes que el servidor web (fase máxima): termina la corrida ({@code RUN_ENDED} con
 * {@code STOPPED}), espera a que ese evento salga por WebSocket y cierra las sesiones con {@code GOING_AWAY}. Todo
 * dentro de {@code twin.engine.shutdown-timeout-ms}; después se apagan el scheduler y el publicador.
 */
public final class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    private final SimulationControlUseCase simulation;
    private final WebSocketTelemetryPublisher publisher;
    private final Duration timeout;
    private volatile boolean running;

    public GracefulShutdown(
            SimulationControlUseCase simulation, WebSocketTelemetryPublisher publisher, Duration timeout) {
        this.simulation = simulation;
        this.publisher = publisher;
        this.timeout = timeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        Instant deadline = Instant.now().plus(timeout);
        try {
            simulation.control(Action.STOP, OptionalInt.empty()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!publisher.awaitDrained(deadline)) {
                log.warn("La cola de WebSocket no se vació en {} ms", timeout.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("El motor no confirmó el STOP al apagar: {}", e.toString());
        } finally {
            publisher.closeSessions(CloseStatus.GOING_AWAY);
            running = false;
            log.info("Gemelo detenido");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
