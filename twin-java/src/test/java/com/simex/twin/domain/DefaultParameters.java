package com.simex.twin.domain;

import com.simex.twin.domain.control.thermal.ColdJunctionMode;
import com.simex.twin.domain.control.thermal.FirmwareParameters;
import com.simex.twin.domain.plant.sensing.SensingParameters;
import com.simex.twin.domain.plant.thermal.ThermalParameters;
import com.simex.twin.domain.shared.SimDuration;

/**
 * Parámetros por defecto del plan (sección 6), de los que salen los valores del anexo A. {@code TwinPropertiesTest}
 * comprueba que coinciden con {@code application.yml}: si alguien cambia uno, las pruebas del dominio no siguen pasando
 * contra números viejos.
 */
public final class DefaultParameters {

    public static final SimDuration TICK = new SimDuration(100);

    private DefaultParameters() {}

    public static ThermalParameters thermal() {
        return new ThermalParameters(1500, 0.95, 8000, 0.115, new SimDuration(5000), new SimDuration(4000), 25.0, 2500);
    }

    public static SensingParameters sensing() {
        return new SensingParameters(0.041, 250, 12, 3.3, 0.0, 0.3, 0.1);
    }

    public static FirmwareParameters firmware() {
        return new FirmwareParameters(0.041, 250, 12, 3.3, ColdJunctionMode.AMBIENT_SENSOR, 25.0);
    }
}
