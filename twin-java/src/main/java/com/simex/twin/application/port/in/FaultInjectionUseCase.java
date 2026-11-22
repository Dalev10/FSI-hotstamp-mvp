package com.simex.twin.application.port.in;

import com.simex.twin.domain.fault.FaultType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Inyección de fallas en la corrida actual. */
public interface FaultInjectionUseCase {

    /**
     * Se completa con el {@code faultId}, o vacío si no hay corrida activa.
     *
     * @throws com.simex.twin.domain.shared.DomainException si los parámetros no son válidos para el tipo
     */
    CompletableFuture<Optional<String>> inject(FaultType type, Map<String, Object> params, Optional<Double> durationS);

    /** Se completa con {@code true} si la falla existía. */
    CompletableFuture<Boolean> remove(String faultId);

    List<CatalogEntry> catalog();

    /** @param defaults valor por defecto de cada parámetro que lo tiene */
    record CatalogEntry(FaultType type, Set<String> params, Map<String, Object> defaults) {}
}
