package com.simex.twin.application.port.in;

/** Latido del agente: el motor lo registra con su reloj de pared. */
public interface HeartbeatUseCase {

    void heartbeat();
}
