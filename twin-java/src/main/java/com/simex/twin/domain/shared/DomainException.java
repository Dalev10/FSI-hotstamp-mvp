package com.simex.twin.domain.shared;

import java.io.Serial;

/** Violación de una invariante o regla del dominio. */
public class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DomainException(String message) {
        super(message);
    }
}
