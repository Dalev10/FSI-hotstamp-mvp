package com.simex.twin.adapter.out.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simex.twin.application.EngineFixture;
import com.simex.twin.application.engine.Frames.Envelope;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** Un cliente que no lee no frena al motor: la cola se acota descartando telemetría vieja. */
class WebSocketTelemetryPublisherTest {

    private static final int CAPACITY = 100;
    private static final int FRAMES = 5_000;

    @Test
    void aStuckClientDoesNotBlockTheEngineAndOldTelemetryIsDropped() throws Exception {
        EngineFixture f = new EngineFixture();
        CountDownLatch never = new CountDownLatch(1);
        WebSocketSession stuck = mock(WebSocketSession.class);
        when(stuck.getId()).thenReturn("lento");
        when(stuck.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
                    never.await(); // la red no avanza
                    return null;
                })
                .when(stuck)
                .sendMessage(any(WebSocketMessage.class));

        try (WebSocketTelemetryPublisher publisher =
                new WebSocketTelemetryPublisher(CAPACITY, JsonMapper.builder().build(), f.engine::view, f.clock)) {
            publisher.start();
            publisher.register(stuck, false); // el hello deja al hilo difusor bloqueado

            long started = System.nanoTime();
            for (int i = 1; i <= FRAMES; i++) {
                publisher.publishTelemetry(frame(f, i));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            assertThat(publisher.dropped()).isGreaterThanOrEqualTo(FRAMES - CAPACITY - 1L);
        }
    }

    private static TelemetryFrame frame(EngineFixture f, long seq) {
        Envelope envelope =
                new Envelope(seq, "00000000-0000-0000-0000-000000000001", Instant.EPOCH, Instant.EPOCH, seq * 1000);
        return new TelemetryFrame(envelope, envelope, 1, f.engine.view().snapshot());
    }
}
