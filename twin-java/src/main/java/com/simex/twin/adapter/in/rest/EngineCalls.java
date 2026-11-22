package com.simex.twin.adapter.in.rest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Espera acotada de las respuestas del motor y los errores que el manejador traduce a HTTP. */
final class EngineCalls {

    private EngineCalls() {}

    /** @throws EngineUnavailableException si el motor no responde en {@code timeoutMs} (503) */
    static <T> T await(CompletableFuture<T> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EngineUnavailableException(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnavailableException(timeoutMs);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }

    static final class EngineUnavailableException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        EngineUnavailableException(long timeoutMs) {
            super("El motor no respondió en " + timeoutMs + " ms");
        }
    }

    static final class NoActiveRunException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        NoActiveRunException() {
            super("No hay una corrida activa");
        }
    }

    static final class NotFoundException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        NotFoundException(String detail) {
            super(detail);
        }
    }
}
