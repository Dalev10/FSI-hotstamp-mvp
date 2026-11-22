package com.simex.twin.application.port.out;

import com.simex.twin.domain.scenario.Scenario;
import java.util.List;
import java.util.Optional;

/** Escenarios disponibles. */
public interface ScenarioRepository {

    /** Todos, ordenados por id. */
    List<Scenario> all();

    Optional<Scenario> find(String id);
}
