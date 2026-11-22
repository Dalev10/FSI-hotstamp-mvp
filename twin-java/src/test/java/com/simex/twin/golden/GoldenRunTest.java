package com.simex.twin.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.adapter.contract.ContractMapper;
import com.simex.twin.application.EngineFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Determinismo (sección 9.1): escenario {@code nominal}, semilla 42, 30 min simulados. El SHA-256 de la serie canónica
 * debe coincidir con {@code golden/nominal-42.sha256}. Se regenera solo con {@code -Dgolden.update=true}, y avisando:
 * un cambio rompe la reproducibilidad de las demos (regla 8).
 *
 * <p>La serie canónica son los mensajes JSON de la vista groundTruth (telemetría de cada segundo y eventos) en orden de
 * {@code seq}, sin {@code ts}, {@code simTs} ni {@code runId}, que dependen del reloj de pared.
 */
class GoldenRunTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/nominal-42.sha256");
    private static final int THIRTY_MINUTES_TICKS = 18_000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void nominalSeed42IsReproducible() throws IOException {
        String first = hashOfNominalRun();
        String second = hashOfNominalRun();
        String third = hashOfNominalRun();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);

        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(GOLDEN, first + "\n");
        }
        assertThat(Files.readString(GOLDEN).strip()).as("hash golden").isEqualTo(first);
    }

    private static String hashOfNominalRun() {
        EngineFixture f = new EngineFixture();
        f.service.startRun("nominal", Optional.of(42L), 1, Optional.empty());
        f.engine.runMax(THIRTY_MINUTES_TICKS);

        ContractMapper mapper = new ContractMapper();
        List<ObjectNode> messages = new ArrayList<>();
        f.publisher.telemetry.forEach(t -> messages.add(JSON.valueToTree(mapper.telemetry(t, true))));
        f.publisher.events.forEach(
                e -> messages.add(JSON.valueToTree(mapper.event(e, true).orElseThrow())));
        messages.sort(Comparator.comparingLong(m -> m.get("seq").asLong()));

        MessageDigest digest = sha256();
        for (ObjectNode message : messages) {
            message.remove(List.of("ts", "simTs", "runId"));
            digest.update((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
