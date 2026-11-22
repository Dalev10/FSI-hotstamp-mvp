package com.simex.twin.adapter.out.websocket;

import com.simex.twin.adapter.contract.ContractMapper;
import com.simex.twin.application.engine.Frames.Envelope;
import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.application.engine.SimulationEngine;
import com.simex.twin.application.engine.SimulationEngine.EngineView;
import com.simex.twin.application.port.out.TelemetryPublisher;
import com.simex.twin.application.port.out.WallClock;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

/**
 * Difunde por {@code /ws/telemetry}. El motor solo encola (nunca se bloquea); el hilo {@code ws-broadcaster} serializa
 * una vez por vista y envía. Con la cola llena se descarta la telemetría más vieja, nunca un evento. Las altas de
 * sesión pasan por la misma cola: el {@code hello} sale en orden con la telemetría y no deja huecos de {@code seq}.
 */
public final class WebSocketTelemetryPublisher implements TelemetryPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTelemetryPublisher.class);
    // Límites de envío por sesión de la sección 3.5 del plan.
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;
    private static final long DRAIN_POLL_MS = 10;

    private final BlockingDeque<Object> queue = new LinkedBlockingDeque<>();
    private final int capacity;
    private final AtomicLong dropped = new AtomicLong();
    // Encolados y aún no difundidos (incluye el que se está enviando): permite vaciar la cola al apagar.
    private final AtomicInteger pending = new AtomicInteger();
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final ContractMapper mapper = new ContractMapper();
    private final Supplier<EngineView> view;
    private final WallClock clock;
    private final Thread broadcaster;
    // Solo los usa el hilo ws-broadcaster.
    private @Nullable String lastRunId;
    private long lastPublicSeq;
    private long lastGroundTruthSeq;

    /** @param view la vista del motor, perezosa: el motor se construye con este publicador */
    public WebSocketTelemetryPublisher(int capacity, ObjectMapper json, Supplier<EngineView> view, WallClock clock) {
        this.capacity = capacity;
        this.json = json;
        this.view = view;
        this.clock = clock;
        this.broadcaster = Thread.ofPlatform().name("ws-broadcaster").daemon().unstarted(this::loop);
    }

    public void start() {
        broadcaster.start();
    }

    @Override
    public void close() {
        broadcaster.interrupt();
    }

    @Override
    public void publishTelemetry(TelemetryFrame frame) {
        enqueue(frame);
    }

    @Override
    public void publishEvent(EventFrame frame) {
        enqueue(frame);
    }

    /** Sesiones suscritas ({@code twin.ws.sessions}). */
    public int sessions() {
        return subscribers.size();
    }

    /** Espera a que se difunda lo encolado, como mucho hasta {@code deadline}; devuelve si se vació. */
    public boolean awaitDrained(Instant deadline) throws InterruptedException {
        while (pending.get() > 0) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            Thread.sleep(DRAIN_POLL_MS);
        }
        return true;
    }

    /** Cierra todas las sesiones (al apagar: {@code GOING_AWAY}). */
    public void closeSessions(CloseStatus status) {
        for (Subscriber subscriber : subscribers.values()) {
            subscribers.remove(subscriber.session().getId());
            try {
                subscriber.session().close(status);
            } catch (IOException | RuntimeException e) {
                log.debug(
                        "No se pudo cerrar la sesión {}: {}",
                        subscriber.session().getId(),
                        e.getMessage());
            }
        }
    }

    /** Telemetrías descartadas por cola llena desde el arranque. */
    public long dropped() {
        return dropped.get();
    }

    void register(WebSocketSession session, boolean groundTruth) {
        enqueue(new Registration(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES),
                groundTruth));
    }

    void unregister(String sessionId) {
        subscribers.remove(sessionId);
    }

    private synchronized void enqueue(Object item) {
        if (queue.size() >= capacity) {
            for (Iterator<Object> it = queue.iterator(); it.hasNext(); ) {
                if (it.next() instanceof TelemetryFrame) {
                    it.remove();
                    dropped.incrementAndGet();
                    pending.decrementAndGet();
                    break;
                }
            }
        }
        pending.incrementAndGet();
        queue.add(item);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                dispatch(item);
            } catch (RuntimeException e) {
                // Una excepción sin capturar mataría el hilo y dejaría de difundir para siempre.
                log.warn("No se pudo difundir {}", item.getClass().getSimpleName(), e);
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private void dispatch(Object item) {
        switch (item) {
            case TelemetryFrame f -> {
                track(f.publicEnvelope(), f.groundTruthEnvelope());
                broadcast(gt -> Optional.of(mapper.telemetry(f, gt)));
            }
            case EventFrame f -> {
                track(f.publicEnvelope().orElse(null), f.groundTruthEnvelope());
                broadcast(gt -> mapper.event(f, gt));
            }
            case Registration r -> {
                EngineView current = view.get();
                boolean sameRun = Objects.equals(current.status().runId().orElse(null), lastRunId);
                long seq = !sameRun ? 0 : r.groundTruth() ? lastGroundTruthSeq : lastPublicSeq;
                Subscriber subscriber = new Subscriber(r.session(), r.groundTruth());
                if (send(subscriber, write(mapper.hello(current, seq, r.groundTruth(), clock.now())))) {
                    subscribers.put(r.session().getId(), subscriber);
                }
            }
            default -> throw new IllegalStateException("Elemento desconocido en la cola: " + item);
        }
    }

    private void track(@Nullable Envelope publicEnvelope, Envelope groundTruthEnvelope) {
        if (!groundTruthEnvelope.runId().equals(lastRunId)) {
            lastRunId = groundTruthEnvelope.runId();
            MDC.put(SimulationEngine.MDC_RUN_ID, lastRunId);
            lastPublicSeq = 0;
        }
        lastGroundTruthSeq = groundTruthEnvelope.seq();
        if (publicEnvelope != null) {
            lastPublicSeq = publicEnvelope.seq();
        }
    }

    /** Serializa como mucho una vez por vista; {@code message} da vacío si el mensaje no es de esa vista. */
    private void broadcast(Function<Boolean, Optional<?>> message) {
        String[] cache = new String[2];
        for (Subscriber subscriber : subscribers.values()) {
            int index = subscriber.groundTruth() ? 1 : 0;
            if (cache[index] == null) {
                cache[index] =
                        message.apply(subscriber.groundTruth()).map(this::write).orElse("");
            }
            if (!cache[index].isEmpty()) {
                send(subscriber, cache[index]);
            }
        }
    }

    private String write(Object message) {
        return json.writeValueAsString(message);
    }

    private boolean send(Subscriber subscriber, String text) {
        try {
            subscriber.session().sendMessage(new TextMessage(text));
            return true;
        } catch (IOException | RuntimeException e) {
            // Cliente lento (límite de tiempo o de búfer) o caído: se le cierra para no frenar a los demás.
            log.debug("Sesión {} descartada: {}", subscriber.session().getId(), e.getMessage());
            subscribers.remove(subscriber.session().getId());
            try {
                subscriber.session().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException | RuntimeException ignored) {
                // ya estaba cerrada
            }
            return false;
        }
    }

    private record Registration(WebSocketSession session, boolean groundTruth) {}

    private record Subscriber(WebSocketSession session, boolean groundTruth) {}
}
