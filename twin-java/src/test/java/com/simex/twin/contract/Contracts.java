package com.simex.twin.contract;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Acceso a {@code contracts/} desde las pruebas: lectura de documentos y validación contra JSON Schema 2020-12. */
final class Contracts {

    static final Path DIR = Path.of(System.getProperty("contracts.dir", "../contracts"))
            .toAbsolutePath()
            .normalize();
    static final Path EXAMPLES = DIR.resolve("examples");
    static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    // IRI virtual (.invalid nunca resuelve): el cargador de file: del validador no admite rutas con espacios.
    private static final String BASE_IRI = "https://contracts.invalid/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            registry -> registry.resourceLoaders(loaders -> loaders.values(list -> list.add(0, iri -> {
                String value = iri.toString();
                return value.startsWith(BASE_IRI)
                        ? () -> Files.newInputStream(DIR.resolve(value.substring(BASE_IRI.length())))
                        : null;
            }))));

    private Contracts() {}

    /** {@code contracts/schemas/<name>.schema.json}. */
    static Schema schema(String name) {
        return REGISTRY.getSchema(SchemaLocation.of(BASE_IRI + "schemas/" + name + ".schema.json"));
    }

    /** {@code openapi.yaml#/components/schemas/<component>}. */
    static Schema openApiComponent(String component) {
        return REGISTRY.getSchema(SchemaLocation.of(BASE_IRI + "openapi.yaml#/components/schemas/" + component));
    }

    /**
     * Esquema de un ejemplo según su nombre: {@code <esquema>[.<variante>].json} o
     * {@code rest/<Componente>[.<variante>].json}.
     */
    static Schema schemaForExample(String example) {
        boolean rest = example.startsWith("rest/");
        String file = rest ? example.substring("rest/".length()) : example;
        String name = file.substring(0, file.indexOf('.'));
        return rest ? openApiComponent(name) : schema(name);
    }

    /** Errores de validación, con los formatos ({@code uuid}, {@code date-time}…) como aserciones. */
    static List<String> errors(Schema schema, JsonNode instance) {
        return schema
                .validate(instance, ctx -> ctx.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(Object::toString)
                .toList();
    }

    static JsonNode read(Path file) {
        try {
            String text = Files.readString(file);
            return (file.toString().endsWith(".yaml") ? YAML : JSON).readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
