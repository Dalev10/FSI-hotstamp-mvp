package com.simex.twin.domain.control.sequence;

import java.util.Optional;

/**
 * Un estado de la secuencia (patrón State): fija sus salidas en {@code ctx.output} y decide su transición. Un
 * {@code Optional} vacío significa quedarse.
 */
interface StateHandler {

    default void onEnter(SequenceContext ctx) {}

    Optional<MachineState> onScan(SequenceContext ctx);

    default void onExit(SequenceContext ctx) {}
}
