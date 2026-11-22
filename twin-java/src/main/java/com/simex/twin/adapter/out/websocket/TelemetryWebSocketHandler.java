package com.simex.twin.adapter.out.websocket;

import java.net.URI;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/** {@code /ws/telemetry}: canal de solo lectura; {@code ?groundTruth=true} elige la vista con la verdad de planta. */
public final class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketTelemetryPublisher publisher;

    public TelemetryWebSocketHandler(WebSocketTelemetryPublisher publisher) {
        this.publisher = publisher;
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
        publisher.register(session, groundTruth);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        publisher.unregister(session.getId());
    }

    /** Se ignora lo que envíe el cliente (texto lo ignora ya la clase base). */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {}
}
