package com.simex.twin.domain.plant.conveyor;

import com.simex.twin.domain.shared.DutyCycle;
import com.simex.twin.domain.shared.SimDuration;

/**
 * Motor de la banda: {@code v_obj = v_nominal · pwm / fricción}, alcanzada con aceleración máxima {@code a} (también al
 * frenar). La cinemática es exacta con aceleración constante por tramos, así que la distancia de frenado no depende de
 * dónde cae el tick.
 */
public final class Motor {

    private final double nominalSpeedMps;
    private final double accelMps2;
    private final double ratedW;
    private double speedMps;

    public Motor(double nominalSpeedMps, double accelMps2, double ratedW) {
        this.nominalSpeedMps = nominalSpeedMps;
        this.accelMps2 = accelMps2;
        this.ratedW = ratedW;
    }

    /** Avanza {@code dt} y devuelve la distancia recorrida por la banda. */
    public double advance(DutyCycle pwm, double frictionFactor, SimDuration dt) {
        double targetMps = nominalSpeedMps * pwm.value() / frictionFactor;
        double dtS = dt.seconds();
        double deltaMps = targetMps - speedMps;
        double timeToTargetS = Math.abs(deltaMps) / accelMps2;
        double distanceM;
        if (timeToTargetS >= dtS) {
            double endMps = speedMps + Math.signum(deltaMps) * accelMps2 * dtS;
            distanceM = (speedMps + endMps) / 2 * dtS;
            speedMps = endMps;
        } else {
            distanceM = (speedMps + targetMps) / 2 * timeToTargetS + targetMps * (dtS - timeToTargetS);
            speedMps = targetMps;
        }
        return distanceM;
    }

    public double speedMps() {
        return speedMps;
    }

    /** Potencia eléctrica: {@code P_nominal · pwm · fricción}. */
    public double powerW(DutyCycle pwm, double frictionFactor) {
        return ratedW * pwm.value() * frictionFactor;
    }
}
