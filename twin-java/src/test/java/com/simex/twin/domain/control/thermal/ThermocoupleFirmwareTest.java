package com.simex.twin.domain.control.thermal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.simex.twin.domain.DefaultParameters;
import org.junit.jupiter.api.Test;

/** Filas de medición del anexo A, del lado del firmware (cuentas → °C), con la junta fría a 25 °C. */
class ThermocoupleFirmwareTest {

    private static final double AMBIENT_READING_C = 25.0;

    private final ThermocoupleFirmware firmware = new ThermocoupleFirmware(DefaultParameters.firmware());

    @Test
    void oneLsbIsPoint0786Degrees() {
        double lsbC = firmware.temperatureC(1, AMBIENT_READING_C) - firmware.temperatureC(0, AMBIENT_READING_C);

        assertThat(lsbC).isCloseTo(0.0786, within(0.00005));
    }

    @Test
    void annexReadingsConvertToTheExpectedTemperatures() {
        assertThat(firmware.temperatureC(477, AMBIENT_READING_C)).isCloseTo(62.50, within(0.005));
        assertThat(firmware.temperatureC(480, AMBIENT_READING_C)).isCloseTo(62.74, within(0.005));
        assertThat(firmware.temperatureC(4095, AMBIENT_READING_C)).isCloseTo(346.95, within(0.005));
    }

    @Test
    void inputMillivoltsAreReconstructedFromCounts() {
        assertThat(firmware.inputMillivolts(477)).isCloseTo(1.5376, within(0.0001));
    }

    @Test
    void fixedColdJunctionIgnoresTheAmbientReading() {
        FirmwareParameters d = DefaultParameters.firmware();
        ThermocoupleFirmware fixed = new ThermocoupleFirmware(new FirmwareParameters(
                d.seebeckMvPerC(), d.amplifierGain(), d.adcBits(), d.adcVrefV(), ColdJunctionMode.FIXED, 25.0));

        assertThat(fixed.temperatureC(477, 10.0)).isCloseTo(62.50, within(0.005));
        assertThat(firmware.temperatureC(477, 10.0)).isCloseTo(47.50, within(0.005));
    }
}
