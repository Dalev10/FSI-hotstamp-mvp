package com.simex.twin.application.port.out;

import com.simex.twin.application.engine.Frames.EventFrame;
import com.simex.twin.application.engine.Frames.TelemetryFrame;

/**
 * Difusión de telemetría y eventos. Se llama desde el hilo del motor: la implementación no debe bloquearlo (encola y
 * envía desde su propio hilo).
 */
public interface TelemetryPublisher {

    void publishTelemetry(TelemetryFrame frame);

    void publishEvent(EventFrame frame);
}
