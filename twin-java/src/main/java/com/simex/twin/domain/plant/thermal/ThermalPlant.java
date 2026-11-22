package com.simex.twin.domain.plant.thermal;

import com.simex.twin.domain.plant.PlantModifiers;
import com.simex.twin.domain.shared.DomainException;
import com.simex.twin.domain.shared.SimDuration;

/**
 * Balance de energía del troquel (masa concentrada con retardo de transporte y del sensor), integrado con Euler
 * explícito:
 *
 * <pre>
 * C · dT/dt = η_eff · P · u(t − θ_d) − (T − T_amb_eff) / R_th_eff − q_pieza
 * dT_s/dt   = (T − T_s) / τ_s
 * </pre>
 */
public final class ThermalPlant {

    private final ThermalParameters parameters;
    private final DelayLine heaterDelay;
    private final FirstOrderLag sensorTip;
    private double platenTempC;

    public ThermalPlant(ThermalParameters parameters, SimDuration tick) {
        if (parameters.transportDelay().ms() % tick.ms() != 0) {
            throw new DomainException("El retardo de transporte debe ser múltiplo del tick");
        }
        this.parameters = parameters;
        this.heaterDelay =
                new DelayLine(Math.toIntExact(parameters.transportDelay().ms() / tick.ms()));
        this.sensorTip = new FirstOrderLag(parameters.sensorTau(), parameters.initialTempC());
        this.platenTempC = parameters.initialTempC();
    }

    /**
     * Avanza un tick.
     *
     * @param heaterFraction fracción de potencia aplicada en este tick (el SSR da 0 o 1)
     * @param ambientC temperatura ambiente del escenario, antes de las fallas
     * @param pieceHeatW calor que retira la pieza en contacto con el troquel (0 si no hay)
     */
    public void advance(double heaterFraction, double ambientC, double pieceHeatW, PlantModifiers m, SimDuration dt) {
        double delayedFraction = heaterDelay.shift(heaterFraction);
        double efficiency = parameters.heaterEfficiency() * m.heaterEfficiencyFactor();
        double rthKPerW = parameters.rthKPerW() * m.rthFactor();
        double netPowerW = efficiency * parameters.heaterPowerW() * delayedFraction
                - (platenTempC - m.effectiveAmbientC(ambientC)) / rthKPerW
                - pieceHeatW;
        sensorTip.update(platenTempC, dt);
        platenTempC += netPowerW * dt.seconds() / parameters.capacitanceJPerK();
    }

    /** Temperatura real del troquel. */
    public double platenTempC() {
        return platenTempC;
    }

    /** Temperatura de la punta de la termocupla, que sigue al troquel con retardo. */
    public double sensorTipTempC() {
        return sensorTip.value();
    }
}
