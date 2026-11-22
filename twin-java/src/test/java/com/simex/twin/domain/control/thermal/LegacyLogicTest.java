package com.simex.twin.domain.control.thermal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/** Las 16 filas de {@code legacy-truth-table.csv}; ver su cabecera sobre el origen de la tabla. */
class LegacyLogicTest {

    @ParameterizedTest(name = "S={0} SP={1} SI={2} T={3}")
    @CsvFileSource(resources = "/legacy-truth-table.csv", numLinesToSkip = 4) // 3 comentarios y la cabecera
    void reproducesTheTruthTable(
            int s, int sp, int si, int t, int metal, int plastic, int green, int heater, int alarm, int motor) {
        LegacyLogic.Outputs outputs = LegacyLogic.evaluate(s == 1, sp == 1, si == 1, t == 1);

        assertThat(outputs)
                .isEqualTo(new LegacyLogic.Outputs(
                        metal == 1, plastic == 1, green == 1, heater == 1, alarm == 1, motor == 1));
    }

    @Test
    void theTableCoversAllSixteenInputCombinations() throws IOException {
        try (InputStream in = LegacyLogicTest.class.getResourceAsStream("/legacy-truth-table.csv")) {
            long distinctInputs = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.startsWith("#") && !line.startsWith("S,"))
                    .map(line -> line.substring(0, 7)) // "S,SP,SI,T"
                    .distinct()
                    .count();

            assertThat(distinctInputs).isEqualTo(16);
        }
    }
}
