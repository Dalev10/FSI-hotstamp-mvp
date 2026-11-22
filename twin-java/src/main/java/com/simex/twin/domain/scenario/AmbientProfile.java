package com.simex.twin.domain.scenario;

import com.simex.twin.domain.scenario.Scenario.AmbientPoint;
import java.util.List;

/**
 * Temperatura ambiente lineal por tramos. Antes del primer punto vale el primero y después del último, el último; sin
 * puntos, la de las condiciones iniciales.
 */
public final class AmbientProfile {

    private static final double MS_PER_SECOND = 1000.0;

    private final List<AmbientPoint> points;
    private final double constantC;

    public AmbientProfile(List<AmbientPoint> points, double defaultAmbientC) {
        this.points = List.copyOf(points);
        this.constantC = defaultAmbientC;
    }

    public double ambientCAt(long simMs) {
        if (points.isEmpty()) {
            return constantC;
        }
        double atS = simMs / MS_PER_SECOND;
        if (atS <= points.getFirst().atS()) {
            return points.getFirst().ambientC();
        }
        for (int i = 1; i < points.size(); i++) {
            AmbientPoint a = points.get(i - 1);
            AmbientPoint b = points.get(i);
            if (atS <= b.atS()) {
                double span = b.atS() - a.atS();
                return span == 0 ? b.ambientC() : a.ambientC() + (b.ambientC() - a.ambientC()) * (atS - a.atS()) / span;
            }
        }
        return points.getLast().ambientC();
    }
}
