package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.Percent;

/**
 * PID en forma paralela con salida en %: {@code u = kp·e + I − kd·dT_meas/dt_f}, con {@code e = setpoint − T_meas}.
 *
 * <ul>
 *   <li>Derivada sobre la medición (un cambio de setpoint no da patada derivativa), filtrada con
 *       {@code τ_f = (kd/kp)/N} por Euler implícito, estable aunque τ_f sea menor que el muestreo.
 *   <li>Anti-windup por integración condicional: no integra si la salida satura y el error empuja hacia el mismo lado.
 *   <li>Cambio de ganancias sin salto: {@code I} absorbe la diferencia de los términos P y D.
 *   <li>{@link #reset} sin salto: en el próximo muestreo, {@code I} se inicializa para que la salida sea la vigente.
 * </ul>
 */
public final class PidController implements TemperatureController {

    private final double sampleS;
    private final double derivativeFilterN;
    private final SampleClock clock;
    private double setpointC;
    private double kp;
    private double ki;
    private double kd;
    private double integral;
    private double filteredDerivative;
    private double previousMeasurementC = Double.NaN;
    private double lastErrorC;
    private Percent output = new Percent(0);
    private double pendingBumplessOutput = Double.NaN;

    public PidController(PidParameters parameters, double setpointC) {
        requirePositiveKp(parameters.kp());
        this.sampleS = parameters.sample().seconds();
        this.derivativeFilterN = parameters.derivativeFilterN();
        this.clock = new SampleClock(parameters.sample());
        this.kp = parameters.kp();
        this.ki = parameters.ki();
        this.kd = parameters.kd();
        this.setpointC = setpointC;
    }

    @Override
    public Percent compute(ControllerInput in) {
        if (!clock.isDue(in.now())) {
            return output;
        }
        double measuredC = in.measuredTempC();
        double errorC = setpointC - measuredC;
        updateDerivative(measuredC);
        lastErrorC = errorC;

        if (!Double.isNaN(pendingBumplessOutput)) {
            integral = pendingBumplessOutput - kp * errorC + kd * filteredDerivative;
            pendingBumplessOutput = Double.NaN;
        } else {
            double proportional = kp * errorC;
            double derivative = kd * filteredDerivative;
            double candidate = proportional + integral + ki * errorC * sampleS - derivative;
            boolean pushingIntoSaturation = (candidate > Percent.MAX && errorC > 0) || (candidate < 0 && errorC < 0);
            if (!pushingIntoSaturation) {
                integral += ki * errorC * sampleS;
            }
        }
        output = new Percent(Math.clamp(kp * errorC + integral - kd * filteredDerivative, 0, Percent.MAX));
        return output;
    }

    @Override
    public void reset(ControllerState state) {
        pendingBumplessOutput = state.output().value();
        previousMeasurementC = Double.NaN;
        filteredDerivative = 0;
        output = state.output();
        clock.sampleNow();
    }

    @Override
    public ControllerState snapshot() {
        return new ControllerState(output);
    }

    /**
     * Arranque en frío al reactivar el térmico: integral en 0 y sin historia. No es sin salto a propósito: desde la
     * salida 0 del térmico apagado, un paso sin salto empezaría con la integral muy negativa y calentaría lento.
     */
    public void restart() {
        integral = 0;
        filteredDerivative = 0;
        previousMeasurementC = Double.NaN;
        pendingBumplessOutput = Double.NaN;
        output = new Percent(0);
        clock.sampleNow();
    }

    /** Ganancias nuevas sin salto en la salida: {@code I} absorbe el cambio de los términos P y D. */
    public void setGains(double newKp, double newKi, double newKd) {
        requirePositiveKp(newKp);
        integral += (kp - newKp) * lastErrorC + (newKd - kd) * filteredDerivative;
        kp = newKp;
        ki = newKi;
        kd = newKd;
    }

    public void setSetpointC(double newSetpointC) {
        setpointC = newSetpointC;
    }

    public double setpointC() {
        return setpointC;
    }

    public double kp() {
        return kp;
    }

    public double ki() {
        return ki;
    }

    public double kd() {
        return kd;
    }

    private void updateDerivative(double measuredC) {
        if (!Double.isNaN(previousMeasurementC)) {
            double rawPerS = (measuredC - previousMeasurementC) / sampleS;
            double filterTauS = kd / kp / derivativeFilterN;
            filteredDerivative += (rawPerS - filteredDerivative) * sampleS / (filterTauS + sampleS);
        }
        previousMeasurementC = measuredC;
    }

    private static void requirePositiveKp(double kp) {
        if (!(kp > 0)) {
            throw new DomainException("kp debe ser positivo: " + kp);
        }
    }
}
