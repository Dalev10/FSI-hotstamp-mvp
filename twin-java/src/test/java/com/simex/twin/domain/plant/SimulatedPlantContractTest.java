package com.simex.twin.domain.plant;

import com.simex.twin.domain.io.PlantPort;
import com.simex.twin.domain.io.PlantPortContract;

class SimulatedPlantContractTest extends PlantPortContract {

    @Override
    protected PlantPort createPlant() {
        return SimulatedPlantTest.plant((now, profile) -> java.util.List.of(), PlantModifiers.neutral());
    }
}
