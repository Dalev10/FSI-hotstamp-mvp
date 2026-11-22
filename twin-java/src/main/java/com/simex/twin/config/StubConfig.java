package com.simex.twin.config;

import com.simex.twin.adapter.out.websocket.stub.StubTelemetryHandler;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import tools.jackson.databind.ObjectMapper;

/** Cableado del stub temporal (perfil {@code stub}, T1). Se elimina en T9 junto con los adaptadores stub. */
@Configuration(proxyBeanMethods = false)
@Profile("stub")
@EnableWebSocket
public class StubConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    StubTelemetryHandler stubTelemetryHandler(
            TwinProperties properties,
            ObjectMapper mapper,
            @Value("${twin.stub.examples-dir:../contracts/examples}") String examplesDir)
            throws IOException {
        return new StubTelemetryHandler(
                Path.of(examplesDir), properties.telemetry().periodSimMs(), mapper);
    }

    @Bean
    WebSocketConfigurer stubTelemetryEndpoint(StubTelemetryHandler handler, TwinProperties properties) {
        return registry -> registry.addHandler(handler, "/ws/telemetry")
                .setAllowedOrigins(properties.telemetry().allowedOrigins().toArray(String[]::new));
    }
}
