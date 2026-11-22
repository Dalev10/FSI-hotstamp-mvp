package com.simex.twin.domain;

import com.simex.twin.domain.control.thermal.ColdJunctionMode;
import com.simex.twin.domain.control.thermal.FirmwareParameters;
import com.simex.twin.domain.control.thermal.LegacyParameters;
import com.simex.twin.domain.control.thermal.PidParameters;
import com.simex.twin.domain.plant.PlantParameters;
import com.simex.twin.domain.plant.conveyor.ConveyorParameters;
import com.simex.twin.domain.plant.pneumatics.PneumaticParameters;
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

    /** La cara del pistón va de −0,04 a +0,04 m respecto del inductivo (decisión 1 de la T4). */
    public static ConveyorParameters conveyor() {
        return new ConveyorParameters(0.60, 1.50, 1.95, 0.06, 0.15, 0.25, 2.0, 100, -0.04, 0.04, 0.003, 0.05, 0.15);
    }

    public static PneumaticParameters pneumatics() {
        return new PneumaticParameters(350, new SimDuration(1000));
    }

    public static PlantParameters plant() {
        return new PlantParameters(thermal(), sensing(), conveyor(), pneumatics());
    }

    public static FirmwareParameters firmware() {
        return new FirmwareParameters(0.041, 250, 12, 3.3, ColdJunctionMode.AMBIENT_SENSOR, 25.0);
    }

    public static final double DEFAULT_SETPOINT_C = 62.5;
    public static final SimDuration SSR_WINDOW = new SimDuration(2000);

    public static PidParameters pid() {
        return new PidParameters(10.0, 0.1, 0.0, new SimDuration(1000), 10);
    }

    public static LegacyParameters legacy() {
        return new LegacyParameters(new SimDuration(1010), 50.0);
    }
}
