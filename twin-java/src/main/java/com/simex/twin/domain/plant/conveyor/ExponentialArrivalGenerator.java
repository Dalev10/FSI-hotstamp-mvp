package com.simex.twin.domain.plant.conveyor;

import com.simex.twin.domain.shared.RandomStream;
import com.simex.twin.domain.shared.SimTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Llegadas de Poisson: tiempo entre llegadas exponencial con media {@code meanIntervalS}; cada pieza es metálica con
 * probabilidad {@code metalRatio}. Cada intervalo se sortea con el perfil vigente en ese momento.
 */
public final class ExponentialArrivalGenerator implements ArrivalGenerator {

    private static final double MS_PER_SECOND = 1000.0;

    private final RandomStream intervals;
    private final RandomStream types;
    private double nextArrivalMs = Double.NaN;

    public ExponentialArrivalGenerator(RandomStream intervals, RandomStream types) {
        this.intervals = intervals;
        this.types = types;
    }

    @Override
    public List<PieceType> arrivalsUntil(SimTime now, ArrivalProfile profile) {
        if (Double.isNaN(nextArrivalMs)) {
            nextArrivalMs = intervalMs(profile);
        }
        List<PieceType> arrived = new ArrayList<>();
        while (nextArrivalMs <= now.ms()) {
            arrived.add(types.nextDouble() < profile.metalRatio() ? PieceType.METAL : PieceType.PLASTIC);
            nextArrivalMs += intervalMs(profile);
        }
        return arrived;
    }

    private double intervalMs(ArrivalProfile profile) {
        return -profile.meanIntervalS() * MS_PER_SECOND * StrictMath.log(1.0 - intervals.nextDouble());
    }
}
