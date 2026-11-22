package com.simex.twin.domain.scenario;

import com.simex.twin.domain.control.command.MachineCommand;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.FaultType;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.shared.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Escenario reproducible (sección 4.11). El constructor valida sus invariantes; el formato del archivo lo valida
 * {@code contracts/schemas/scenario.schema.json} en las pruebas.
 */
public record Scenario(
        String id,
        String description,
        long seed,
        double durationSimS,
        ControlMode controlMode,
        InitialConditions initialConditions,
        ArrivalProfile arrivals,
        List<AmbientPoint> ambientProfile,
        List<ScheduledAction> actions,
        List<ScheduledFault> faults) {

    public record InitialConditions(double platenTempC, double ambientC) {}

    public record AmbientPoint(double atS, double ambientC) {}

    public record ScheduledAction(double atS, MachineCommand command) {}

    /** @param durationS vacío: dura hasta que se retira (o 120 s si es una ráfaga de metales) */
    public record ScheduledFault(double atS, FaultType type, Map<String, Object> params, Optional<Double> durationS) {}

    public Scenario {
        require(!id.isBlank(), "el escenario necesita un id");
        require(durationSimS > 0, "durationSimS debe ser positivo");
        require(arrivals.meanIntervalS() > 0, "arrivals.meanIntervalS debe ser positivo");
        require(arrivals.metalRatio() >= 0 && arrivals.metalRatio() <= 1, "arrivals.metalRatio fuera de [0, 1]");
        for (int i = 1; i < ambientProfile.size(); i++) {
            require(ambientProfile.get(i).atS() >= ambientProfile.get(i - 1).atS(), "ambientProfile desordenado");
        }
        require(actions.stream().allMatch(a -> a.atS() >= 0), "una acción tiene atS negativo");
        require(faults.stream().allMatch(f -> f.atS() >= 0), "una falla tiene atS negativo");
        ambientProfile = List.copyOf(ambientProfile);
        actions = List.copyOf(actions);
        faults = List.copyOf(faults);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new DomainException("Escenario inválido: " + message);
        }
    }
}
