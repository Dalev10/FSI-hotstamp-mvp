package com.simex.twin.domain.io;

class FakePlantContractTest extends PlantPortContract {

    @Override
    protected PlantPort createPlant() {
        return new FakePlant();
    }
}
