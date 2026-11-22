package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.adapter.out.scenario.ClasspathScenarioRepository;
import com.simex.twin.domain.scenario.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Sección 9.2: los 9 escenarios cargan y validan contra {@code scenario.schema.json}. */
class ScenarioFilesTest {

    private static final Path SCENARIOS = Path.of("src/main/resources/scenarios");
    private static final List<String> EXPECTED = List.of(
            "deriva-offset",
            "desgaste-motor",
            "frente-frio",
            "fuga-neumatica",
            "nominal",
            "rafaga-metales",
            "resistencia-degradada",
            "ruido-sensor",
            "termocupla-abierta");

    static Stream<Path> scenarioFiles() throws IOException {
        return Files.list(SCENARIOS).sorted().toList().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioFiles")
    void eachFileValidatesAgainstTheContract(Path file) {
        assertThat(Contracts.errors(Contracts.schema("scenario"), Contracts.read(file)))
                .isEmpty();
    }

    @Test
    void theRepositoryLoadsTheNineScenarios() {
        ClasspathScenarioRepository repository =
                new ClasspathScenarioRepository(JsonMapper.builder().build());

        assertThat(repository.all()).extracting(Scenario::id).containsExactlyElementsOf(EXPECTED);
        assertThat(repository.find("nominal")).get().satisfies(s -> {
            assertThat(s.faults()).isEmpty();
            assertThat(s.actions()).hasSize(1);
        });
        assertThat(repository.find("inexistente")).isEmpty();
        assertThat(repository.all())
                .filteredOn(s -> !s.id().equals("nominal"))
                .allSatisfy(s -> assertThat(s.faults())
                        .singleElement()
                        .satisfies(f -> assertThat(f.atS()).isEqualTo(600.0)));
    }
}
