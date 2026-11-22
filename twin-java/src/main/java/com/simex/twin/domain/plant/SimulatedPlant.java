package com.simex.twin.domain.plant;

import com.simex.twin.domain.io.CylinderPosition;
import com.simex.twin.domain.io.PlantPort;
import com.simex.twin.domain.io.ProcessInputs;
import com.simex.twin.domain.io.ProcessOutputs;
import com.simex.twin.domain.plant.conveyor.ArrivalGenerator;
import com.simex.twin.domain.plant.conveyor.ArrivalProfile;
import com.simex.twin.domain.plant.conveyor.Conveyor;
import com.simex.twin.domain.plant.conveyor.Motor;
import com.simex.twin.domain.plant.conveyor.Piece;
import com.simex.twin.domain.plant.conveyor.PieceType;
import com.simex.twin.domain.plant.pneumatics.PneumaticCylinder;
import com.simex.twin.domain.plant.sensing.AmbientSensor;
import com.simex.twin.domain.plant.sensing.ThermocoupleChain;
import com.simex.twin.domain.plant.thermal.ThermalPlant;
import com.simex.twin.domain.shared.RandomStreams;
import com.simex.twin.domain.shared.SimDuration;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongToDoubleFunction;
import java.util.function.Supplier;

/**
 * La máquina física simulada: térmica, medición, banda, piezas y neumática detrás de {@link PlantPort}. Las fallas
 * entran por {@code modifiers}, que se consulta en cada lectura y en cada avance.
 */
public final class SimulatedPlant implements PlantPort {

    private final PlantParameters parameters;
    private final ArrivalGenerator arrivals;
    private final ArrivalProfile baseArrivals;
    private final LongToDoubleFunction ambientCAtMs;
    private final Supplier<PlantModifiers> modifiers;
    private final ThermalPlant thermal;
    private final ThermocoupleChain thermocouple;
    private final AmbientSensor ambientSensor;
    private final Motor motor;
    private final Conveyor conveyor;
    private final PneumaticCylinder discardCylinder;
    private final PneumaticCylinder stampCylinder;
    private final List<PlantEvent> events = new ArrayList<>();
    private ProcessOutputs outputs = ProcessOutputs.SAFE;
    private SimTime now = SimTime.ZERO;
    private int piecesArrived;

    /**
     * @param ambientCAtMs temperatura ambiente del escenario en cada instante simulado (ms), antes de las fallas
     */
    public SimulatedPlant(
            PlantParameters parameters,
            RandomStreams streams,
            ArrivalGenerator arrivals,
            ArrivalProfile baseArrivals,
            LongToDoubleFunction ambientCAtMs,
            Supplier<PlantModifiers> modifiers,
            SimDuration tick) {
        this.parameters = parameters;
        this.arrivals = arrivals;
        this.baseArrivals = baseArrivals;
        this.ambientCAtMs = ambientCAtMs;
        this.modifiers = modifiers;
        this.thermal = new ThermalPlant(parameters.thermal(), tick);
        this.thermocouple = new ThermocoupleChain(parameters.sensing(), streams.get(RandomStreams.Name.TC_NOISE));
        this.ambientSensor = new AmbientSensor(
                parameters.sensing().ambientNoiseSigmaC(), streams.get(RandomStreams.Name.AMBIENT_NOISE));
        var conveyorParameters = parameters.conveyor();
        this.motor = new Motor(
                conveyorParameters.nominalSpeedMps(), conveyorParameters.accelMps2(), conveyorParameters.motorRatedW());
        this.conveyor = new Conveyor(conveyorParameters, streams.get(RandomStreams.Name.INDUCTIVE_MISS));
        this.discardCylinder = new PneumaticCylinder(parameters.pneumatics().strokeMs());
        this.stampCylinder = new PneumaticCylinder(parameters.pneumatics().strokeMs());
    }

    @Override
    public ProcessInputs readInputs() {
        PlantModifiers m = modifiers.get();
        double ambientC = ambientCAtMs.applyAsDouble(now.ms());
        return new ProcessInputs(
                thermocouple.measure(thermal.sensorTipTempC(), ambientC, m),
                ambientSensor.read(ambientC, m),
                conveyor.inductive(),
                conveyor.photoelectric(),
                discardCylinder.position(),
                stampCylinder.position(),
                motor.speedMps());
    }

    @Override
    public void writeOutputs(ProcessOutputs outputs) {
        this.outputs = outputs;
        discardCylinder.command(outputs.discardValve());
        stampCylinder.command(outputs.stampValve());
    }

    @Override
    public void advance(SimDuration dt) {
        PlantModifiers m = modifiers.get();
        double ambientC = ambientCAtMs.applyAsDouble(now.ms());
        SimTime end = now.plus(dt);

        for (PieceType type : arrivals.arrivalsUntil(end, m.arrivalsOr(baseArrivals))) {
            Piece piece = new Piece(nextPieceId(), type, parameters.conveyor().pieceLengthM());
            conveyor.enqueue(piece);
            events.add(new PlantEvent.PieceArrived(piece.id(), type));
        }
        double distanceM = motor.advance(outputs.motorPwm(), m.frictionFactor(), dt);
        conveyor.advance(distanceM, motor.speedMps(), events);

        if (discardCylinder.advance(dt, m.discardStrokeFactor())) {
            conveyor.discardMetal().ifPresent(p -> events.add(new PlantEvent.PieceDiscarded(p.id())));
        }
        stampCylinder.advance(dt, m.stampStrokeFactor());
        double pieceHeatW = stampCylinder.position() == CylinderPosition.EXTENDED ? stampContact(dt) : 0;

        thermal.advance(outputs.ssr() ? 1 : 0, ambientC, pieceHeatW, m, dt);
        now = end;
    }

    /** Hechos de las piezas desde la última llamada. */
    public List<PlantEvent> drainEvents() {
        List<PlantEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    /** Temperatura real del troquel (verdad de terreno). */
    public double platenTempC() {
        return thermal.platenTempC();
    }

    /** Temperatura ambiente real, con las fallas (verdad de terreno). */
    public double realAmbientC() {
        return modifiers.get().effectiveAmbientC(ambientCAtMs.applyAsDouble(now.ms()));
    }

    /** Eficiencia real de la resistencia, con las fallas (verdad de terreno). */
    public double heaterEfficiency() {
        return parameters.thermal().heaterEfficiency() * modifiers.get().heaterEfficiencyFactor();
    }

    /** Potencia eléctrica de la resistencia en este instante (sin η). */
    public double heaterPowerW() {
        return outputs.ssr() ? parameters.thermal().heaterPowerW() : 0;
    }

    public double motorPowerW() {
        return motor.powerW(outputs.motorPwm(), modifiers.get().frictionFactor());
    }

    public int queueLength() {
        return conveyor.queueLength();
    }

    List<Piece> piecesOnBelt() {
        return conveyor.piecesOnBelt();
    }

    /**
     * El troquel extendido sobre una pieza le retira {@code piece-heat-j} repartido en la permanencia, con ese total como
     * tope por pieza. Devuelve el calor retirado en este tick, en W.
     */
    private double stampContact(SimDuration dt) {
        return conveyor.pieceAtStation()
                .map(piece -> {
                    if (!piece.stamped()) {
                        piece.markStamped();
                        events.add(new PlantEvent.StampContact(piece.id(), thermal.platenTempC()));
                    }
                    double pieceHeatJ = parameters.thermal().pieceHeatJ();
                    double rateW =
                            pieceHeatJ / parameters.pneumatics().stampDwell().seconds();
                    double heatJ = Math.min(rateW * dt.seconds(), pieceHeatJ - piece.heatTakenJ());
                    piece.takeHeat(heatJ);
                    return heatJ / dt.seconds();
                })
                .orElse(0.0);
    }

    private String nextPieceId() {
        return String.format(Locale.ROOT, "P-%06d", ++piecesArrived);
    }
}
