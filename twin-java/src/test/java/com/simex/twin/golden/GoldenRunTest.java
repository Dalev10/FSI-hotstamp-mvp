package com.simex.twin.golden;

import static com.simex.twin.domain.DefaultParameters.TICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.adapter.out.scenario.ClasspathScenarioRepository;
import com.simex.twin.domain.DefaultParameters;
import com.simex.twin.domain.machine.ScanResult;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.scenario.ScenarioRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Determinismo (sección 9.1): escenario {@code nominal}, semilla 42, 30 min simulados. El SHA-256 de la serie canónica
 * debe coincidir con {@code golden/nominal-42.sha256}. Se regenera solo con {@code -Dgolden.update=true}, y avisando:
 * un cambio rompe la reproducibilidad de las demos (regla 8).
 *
 * <p>Hasta la T9 la serie canónica es la del dominio (cada snapshot de 1 s simulado y cada evento); en la T9 pasa a
 * ser la de los mensajes JSON del contrato sin {@code ts}, {@code simTs} ni {@code runId}.
 */
class GoldenRunTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/nominal-42.sha256");
    private static final int THIRTY_MINUTES_TICKS = 18_000;
    private static final int TICKS_PER_SECOND = 10;

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
        Scenario nominal = new ClasspathScenarioRepository(JsonMapper.builder().build())
                .find("nominal")
                .orElseThrow();
        ScenarioRunner runner = new ScenarioRunner(
                nominal, nominal.seed(), DefaultParameters.plant(), DefaultParameters.control(), TICK);
        MessageDigest digest = sha256();
        for (int tick = 0; tick < THIRTY_MINUTES_TICKS; tick++) {
            ScanResult result = runner.step(tick * TICK.ms());
            result.events().forEach(e -> line(digest, e.toString()));
            if ((tick + 1) % TICKS_PER_SECOND == 0) {
                line(digest, result.snapshot().toString());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void line(MessageDigest digest, String text) {
        digest.update((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
