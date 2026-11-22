package com.simex.twin.adapter.out.scenario;

import com.simex.twin.adapter.contract.ContractJson;
import com.simex.twin.application.port.out.ScenarioRepository;
import com.simex.twin.domain.control.command.CommandType;
import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.scenario.Scenario;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lee {@code classpath:scenarios/*.json} al construirse. Un archivo que no se puede leer o que viola las invariantes
 * de {@link Scenario} impide el arranque. El formato completo lo validan las pruebas contra el esquema del contrato.
 */
public final class ClasspathScenarioRepository implements ScenarioRepository {

    private static final String PATTERN = "classpath:scenarios/*.json";

    private final Map<String, Scenario> scenarios = new TreeMap<>();

    public ClasspathScenarioRepository(ObjectMapper mapper) {
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(PATTERN)) {
                try (InputStream in = resource.getInputStream()) {
                    Scenario scenario = parse(mapper.readTree(in));
                    if (scenarios.putIfAbsent(scenario.id(), scenario) != null) {
                        throw new IllegalStateException("Escenario repetido: " + scenario.id());
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Escenario inválido en " + resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Scenario> all() {
        return List.copyOf(scenarios.values());
    }

    @Override
    public Optional<Scenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    static Scenario parse(JsonNode json) {
        JsonNode initial = json.required("initialConditions");
        JsonNode arrivals = json.required("arrivals");
        List<Scenario.AmbientPoint> ambient = new ArrayList<>();
        json.path("ambientProfile")
                .forEach(p -> ambient.add(new Scenario.AmbientPoint(
                        p.required("atS").asDouble(), p.required("ambientC").asDouble())));
        List<Scenario.ScheduledAction> actions = new ArrayList<>();
        json.path("actions")
                .forEach(a -> actions.add(
                        new Scenario.ScheduledAction(a.required("atS").asDouble(), command(a.required("command")))));
        List<Scenario.ScheduledFault> faults = new ArrayList<>();
        json.path("faults")
                .forEach(f -> faults.add(new Scenario.ScheduledFault(
                        f.required("atS").asDouble(),
                        FaultType.valueOf(f.required("type").asString()),
                        ContractJson.faultParams(f.path("params")),
                        f.has("durationS") ? Optional.of(f.get("durationS").asDouble()) : Optional.empty())));
        return new Scenario(
                json.required("id").asString(),
                json.required("description").asString(),
                json.required("seed").asLong(),
                json.required("durationSimS").asDouble(),
                ControlMode.valueOf(json.required("controlMode").asString()),
                new Scenario.InitialConditions(
                        initial.required("platenTempC").asDouble(),
                        initial.required("ambientC").asDouble()),
                new ArrivalProfile(
                        arrivals.required("meanIntervalS").asDouble(),
                        arrivals.required("metalRatio").asDouble()),
                ambient,
                actions,
                faults);
    }

    private static MachineCommand command(JsonNode json) {
        return ContractJson.command(CommandType.valueOf(json.required("type").asString()), json.path("params"));
    }
}
