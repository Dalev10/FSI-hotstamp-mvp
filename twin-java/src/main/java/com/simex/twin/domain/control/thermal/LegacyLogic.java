package com.simex.twin.domain.control.thermal;

/**
 * Lógica combinacional del ITO 2 (sección 4.5 del plan). En el código Wokwi la columna "Paro" de la tabla es
 * {@code spresencia} (fotoeléctrico), por eso aquí se llama {@code presence}; el paro de emergencia real es de la capa
 * de seguridad.
 *
 * <pre>
 * Metal       = S ∧ SP ∧ SI
 * Plástico    = S ∧ SP ∧ ¬SI ∧ T
 * Verde       = S ∧ T
 * Resistencia = S ∧ ¬T ∧ (¬SI ∨ SP)
 * Alarma      = S ∧ ¬SP ∧ SI
 * Motor       = S ∧ (SP ⇔ SI)
 * </pre>
 */
public final class LegacyLogic {

    /** Salidas del ITO 2. El gemelo solo usa {@code heater}; las demás quedan para la comparación con la tabla. */
    public record Outputs(
            boolean metal, boolean plastic, boolean green, boolean heater, boolean alarm, boolean motor) {}

    private LegacyLogic() {}

    /**
     * @param start S: máquina en un estado operativo
     * @param presence SP: fotoeléctrico
     * @param inductive SI: inductivo
     * @param tempReached T: temperatura medida ≥ umbral
     */
    public static Outputs evaluate(boolean start, boolean presence, boolean inductive, boolean tempReached) {
        return new Outputs(
                start && presence && inductive,
                start && presence && !inductive && tempReached,
                start && tempReached,
                start && !tempReached && (!inductive || presence),
                start && !presence && inductive,
                start && presence == inductive);
    }
}
