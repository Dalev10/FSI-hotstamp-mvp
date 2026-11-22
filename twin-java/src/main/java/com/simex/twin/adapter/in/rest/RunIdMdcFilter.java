package com.simex.twin.adapter.in.rest;

import com.simex.twin.application.engine.SimulationEngine;
import com.simex.twin.application.port.in.QueryStateUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Pone el {@code runId} vigente en el MDC durante cada petición, para que los logs digan a qué corrida pertenecen. */
@Component
public class RunIdMdcFilter extends OncePerRequestFilter {

    private final QueryStateUseCase query;

    public RunIdMdcFilter(QueryStateUseCase query) {
        this.query = query;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        query.view().status().runId().ifPresent(id -> MDC.put(SimulationEngine.MDC_RUN_ID, id));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(SimulationEngine.MDC_RUN_ID);
        }
    }
}
