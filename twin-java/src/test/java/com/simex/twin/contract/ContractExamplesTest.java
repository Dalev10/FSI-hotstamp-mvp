package com.simex.twin.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Valida {@code contracts/}: ejemplos contra sus esquemas, casos inválidos rechazados y referencias resolubles. */
class ContractExamplesTest {

    static List<String> examples() throws IOException {
        try (Stream<Path> files = Files.walk(Contracts.EXAMPLES)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> Contracts.EXAMPLES.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void exampleIsValidAgainstItsSchema(String example) {
        JsonNode instance = Contracts.read(Contracts.EXAMPLES.resolve(example));

        assertThat(Contracts.errors(Contracts.schemaForExample(example), instance))
                .isEmpty();
    }

    @Test
    void everyMessageSchemaHasAnExample() throws IOException {
        List<String> examples = examples();
        try (Stream<Path> schemas = Files.list(Contracts.DIR.resolve("schemas"))) {
            List<String> withoutExample = schemas.map(
                            p -> p.getFileName().toString().replace(".schema.json", ""))
                    // envelope es abstracto: lo ejercitan los ejemplos de hello, telemetry y event.
                    .filter(name -> !name.equals("envelope"))
                    .filter(name -> examples.stream().noneMatch(e -> e.startsWith(name + ".")))
                    .toList();

            assertThat(withoutExample).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "event, eventType, event.schema.json, /$defs/eventType/enum",
        "command, type, command.schema.json, /$defs/commandType/enum",
        "fault, type, fault.schema.json, /$defs/faultType/enum"
    })
    void everyDeclaredTypeHasAnExample(String prefix, String field, String schemaFile, String enumPointer)
            throws IOException {
        Set<String> declared = new TreeSet<>();
        Contracts.read(Contracts.DIR.resolve("schemas").resolve(schemaFile))
                .at(enumPointer)
                .forEach(value -> declared.add(value.asString()));

        Set<String> covered = examples().stream()
                .filter(e -> e.startsWith(prefix + "."))
                .map(e -> Contracts.read(Contracts.EXAMPLES.resolve(e))
                        .path(field)
                        .asString())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(covered).isEqualTo(declared);
    }

    static List<Arguments> invalidCases() throws IOException {
        try (InputStream in = ContractExamplesTest.class.getResourceAsStream("/contract-invalid-cases.json")) {
            List<Arguments> cases = new ArrayList<>();
            Contracts.JSON
                    .readTree(in)
                    .forEach(c -> cases.add(Arguments.of(
                            c.path("example").asString() + ": " + c.path("why").asString(), c)));
            return cases;
        }
    }

    /** Cada caso parte de un ejemplo válido y lo rompe con {@code set} (puntero → valor) o {@code remove}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void schemaRejectsInvalidInstance(String name, JsonNode invalidCase) {
        String example = invalidCase.path("example").asString();
        JsonNode instance = Contracts.read(Contracts.EXAMPLES.resolve(example));
        invalidCase
                .path("set")
                .properties()
                .forEach(e -> parentOf(instance, e.getKey()).set(leafOf(e.getKey()), e.getValue()));
        invalidCase.path("remove").forEach(p -> parentOf(instance, p.asString()).remove(leafOf(p.asString())));

        assertThat(Contracts.errors(Contracts.schemaForExample(example), instance))
                .as(name)
                .isNotEmpty();
    }

    @Test
    void everyRefInTheContractResolves() throws IOException {
        List<Path> documents;
        try (Stream<Path> schemas = Files.list(Contracts.DIR.resolve("schemas"))) {
            documents = Stream.concat(
                            schemas,
                            Stream.of(Contracts.DIR.resolve("openapi.yaml"), Contracts.DIR.resolve("asyncapi.yaml")))
                    .toList();
        }
        List<String> broken = new ArrayList<>();
        documents.forEach(doc -> collectBrokenRefs(doc, Contracts.read(doc), broken));

        assertThat(broken).isEmpty();
    }

    @Test
    void specDocumentsDeclareTheirVersions() {
        assertThat(Contracts.read(Contracts.DIR.resolve("openapi.yaml"))
                        .path("openapi")
                        .asString())
                .isEqualTo("3.1.0");
        assertThat(Contracts.read(Contracts.DIR.resolve("asyncapi.yaml"))
                        .path("asyncapi")
                        .asString())
                .isEqualTo("3.0.0");
    }

    private static void collectBrokenRefs(Path doc, JsonNode node, List<String> broken) {
        JsonNode ref = node.get("$ref");
        if (node.isObject() && ref != null && ref.isString() && !resolves(doc, ref.asString())) {
            broken.add(doc.getFileName() + " → " + ref.asString());
        }
        node.forEach(child -> collectBrokenRefs(doc, child, broken));
    }

    private static boolean resolves(Path doc, String ref) {
        int hash = ref.indexOf('#');
        String file = hash < 0 ? ref : ref.substring(0, hash);
        String pointer = hash < 0 ? "" : ref.substring(hash + 1);
        Path target = file.isEmpty() ? doc : doc.resolveSibling(file).normalize();
        return Files.exists(target) && !Contracts.read(target).at(pointer).isMissingNode();
    }

    private static ObjectNode parentOf(JsonNode root, String pointer) {
        return (ObjectNode) root.at(pointer.substring(0, pointer.lastIndexOf('/')));
    }

    private static String leafOf(String pointer) {
        return pointer.substring(pointer.lastIndexOf('/') + 1);
    }
}
