package com.simex.twin.adapter.out.websocket.stub;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stub de {@code /ws/telemetry}: recorre en orden los ejemplos {@code telemetry*.json} del contrato, uno por período,
 * reescribiendo el sobre ({@code seq} consecutivo, {@code ts}, {@code simTs}, {@code simTimeMs} y un {@code runId} fijo
 * por arranque). Envía {@code hello} al conectar y quita {@code groundTruth} salvo con {@code ?groundTruth=true}.
 */
public final class StubTelemetryHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StubTelemetryHandler.class);
    // Límites de envío por sesión de la sección 3.5 del plan.
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;
    private static final List<String> ENVELOPE_FIELDS =
            List.of("type", "schemaVersion", "seq", "runId", "ts", "simTs", "simTimeMs");

    private final List<ObjectNode> examples;
    private final long periodMs;
    private final ObjectMapper mapper;
    private final String runId = UUID.randomUUID().toString();
    private final Instant runStartedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    // Un solo hilo publica y atiende las conexiones nuevas: hello y telemetría no se cruzan ni dejan huecos de seq.
    private final ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("stub-telemetry").daemon().factory());
    private long seq;
    private long tick;
    private @Nullable ObjectNode current;

    public StubTelemetryHandler(Path examplesDir, long periodMs, ObjectMapper mapper) throws IOException {
        this.examples = loadTelemetryExamples(examplesDir, mapper);
        this.periodMs = periodMs;
        this.mapper = mapper;
    }

    public void start() {
        publisher.scheduleAtFixedRate(this::publishNext, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        publisher.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        boolean groundTruth = uri != null
                && "true"
                        .equalsIgnoreCase(UriComponentsBuilder.fromUri(uri)
                                .build()
                                .getQueryParams()
                                .getFirst("groundTruth"));
        Subscriber subscriber = new Subscriber(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES),
                groundTruth);
        publisher.execute(() -> {
            send(subscriber, hello());
            subscribers.put(session.getId(), subscriber);
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.remove(session.getId());
    }

    /** Canal de solo lectura: se ignora lo que envíe el cliente. */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {}

    private void publishNext() {
        try {
            ObjectNode message =
                    stamp(examples.get((int) (tick % examples.size())).deepCopy(), ++seq, tick * periodMs);
            tick++;
            current = message;
            subscribers.values().forEach(subscriber -> send(subscriber, message));
        } catch (RuntimeException e) {
            // Una excepción sin capturar cancelaría la tarea periódica para siempre.
            log.warn("No se pudo publicar la telemetría del stub", e);
        }
    }

    private ObjectNode hello() {
        ObjectNode base = current != null ? current : stamp(examples.get(0).deepCopy(), 0, 0);
        ObjectNode hello = mapper.createObjectNode();
        ENVELOPE_FIELDS.forEach(field -> hello.set(field, base.get(field)));
        hello.put("type", "hello");
        ObjectNode snapshot = base.deepCopy();
        snapshot.remove(ENVELOPE_FIELDS);
        hello.set("snapshot", snapshot);
        return hello;
    }

    private ObjectNode stamp(ObjectNode message, long messageSeq, long simTimeMs) {
        message.put("seq", messageSeq);
        message.put("runId", runId);
        message.put("ts", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        message.put("simTs", runStartedAt.plusMillis(simTimeMs).toString());
        message.put("simTimeMs", simTimeMs);
        message.put("speedFactor", 1);
        return message;
    }

    private void send(Subscriber subscriber, ObjectNode message) {
        try {
            subscriber.session().sendMessage(new TextMessage(mapper.writeValueAsString(viewFor(subscriber, message))));
        } catch (IOException | RuntimeException e) {
            log.debug("Sesión {} descartada: {}", subscriber.session().getId(), e.getMessage());
            subscribers.remove(subscriber.session().getId());
        }
    }

    private static ObjectNode viewFor(Subscriber subscriber, ObjectNode message) {
        if (subscriber.groundTruth()) {
            return message;
        }
        ObjectNode publicView = message.deepCopy();
        publicView.remove("groundTruth");
        if (publicView.get("snapshot") instanceof ObjectNode snapshot) {
            snapshot.remove("groundTruth");
        }
        return publicView;
    }

    private static List<ObjectNode> loadTelemetryExamples(Path dir, ObjectMapper mapper) throws IOException {
        List<ObjectNode> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("telemetry.*\\.json"))
                    .sorted()
                    .toList()) {
                loaded.add((ObjectNode) mapper.readTree(Files.readString(file)));
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No hay ejemplos telemetry*.json en " + dir.toAbsolutePath());
        }
        return List.copyOf(loaded);
    }

    private record Subscriber(WebSocketSession session, boolean groundTruth) {}
}
