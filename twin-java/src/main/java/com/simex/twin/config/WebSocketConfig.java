package com.simex.twin.config;

import com.simex.twin.adapter.out.websocket.TelemetryWebSocketHandler;
import com.simex.twin.adapter.out.websocket.WebSocketTelemetryPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** {@code /ws/telemetry} con los orígenes de {@code twin.telemetry.allowed-origins}. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketTelemetryPublisher publisher;
    private final TwinProperties properties;

    public WebSocketConfig(WebSocketTelemetryPublisher publisher, TwinProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TelemetryWebSocketHandler(publisher), "/ws/telemetry")
                .setAllowedOrigins(properties.telemetry().allowedOrigins().toArray(String[]::new));
    }
}
