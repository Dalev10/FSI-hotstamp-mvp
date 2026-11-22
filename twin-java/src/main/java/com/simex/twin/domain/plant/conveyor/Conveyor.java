package com.simex.twin.domain.plant.conveyor;

import com.simex.twin.domain.plant.PlantEvent;
import com.simex.twin.domain.shared.RandomStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Banda con su cola de entrada, sensores e inspección de salida. Todas las piezas viajan a la velocidad de la banda, así
 * que nunca se superponen: basta con respetar el tramo libre al entrar.
 */
public final class Conveyor {

    private final ConveyorParameters parameters;
    private final InductiveSensor inductive;
    private final PhotoelectricSensor photoelectric;
    private final ExitInspection exitInspection;
    private final Deque<Piece> queue = new ArrayDeque<>();
    private final List<Piece> onBelt = new ArrayList<>();

    public Conveyor(ConveyorParameters parameters, RandomStream inductiveMissDraws) {
        this.parameters = parameters;
        this.inductive = new InductiveSensor(parameters, inductiveMissDraws);
        this.photoelectric = new PhotoelectricSensor(parameters.stationAtM());
        this.exitInspection = new ExitInspection(parameters.exitQaAtM());
    }

    public void enqueue(Piece piece) {
        queue.addLast(piece);
    }

    /**
     * Mueve las piezas, sortea las no detecciones del inductivo, retira las que pasan la inspección de salida y, si la
     * banda se mueve y la entrada está libre, hace entrar la siguiente de la cola.
     */
    public void advance(double distanceM, double speedMps, List<PlantEvent> events) {
        for (Iterator<Piece> it = onBelt.iterator(); it.hasNext(); ) {
            Piece piece = it.next();
            double previousFrontM = piece.frontM();
            piece.moveBy(distanceM);
            inductive.onAdvance(piece, previousFrontM, speedMps);
            if (exitInspection.hasExited(piece)) {
                it.remove();
                events.add(new PlantEvent.PieceExited(piece.id(), exitInspection.inspect(piece)));
            }
        }
        if (speedMps > 0 && !queue.isEmpty() && entranceIsFree()) {
            onBelt.add(queue.removeFirst());
        }
    }

    public boolean inductive() {
        return onBelt.stream().anyMatch(inductive::detects);
    }

    public boolean photoelectric() {
        return onBelt.stream().anyMatch(photoelectric::detects);
    }

    public Optional<Piece> pieceAtStation() {
        return onBelt.stream().filter(photoelectric::detects).findFirst();
    }

    /** El pistón de descarte retira el metal cuya cara cubre su centro; si no hay ninguno, no retira nada. */
    public Optional<Piece> discardMetal() {
        double fromM = parameters.inductiveAtM() + parameters.discardFaceFromM();
        double toM = parameters.inductiveAtM() + parameters.discardFaceToM();
        Optional<Piece> target = onBelt.stream()
                .filter(p -> p.isMetal() && fromM <= p.centerM() && p.centerM() <= toM)
                .findFirst();
        target.ifPresent(onBelt::remove);
        return target;
    }

    public int queueLength() {
        return queue.size();
    }

    /** Piezas sobre la banda, de la que entró primero a la última. */
    public List<Piece> piecesOnBelt() {
        return List.copyOf(onBelt);
    }

    private boolean entranceIsFree() {
        return onBelt.stream().allMatch(p -> p.rearM() >= parameters.minSpacingM());
    }
}
