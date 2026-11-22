package com.simex.twin.domain.plant;

import com.simex.twin.domain.plant.conveyor.ConveyorParameters;
import com.simex.twin.domain.plant.pneumatics.PneumaticParameters;
import com.simex.twin.domain.plant.sensing.SensingParameters;
import com.simex.twin.domain.plant.thermal.ThermalParameters;

/** Parámetros físicos de toda la planta simulada. */
public record PlantParameters(
        ThermalParameters thermal,
        SensingParameters sensing,
        ConveyorParameters conveyor,
        PneumaticParameters pneumatics) {}
