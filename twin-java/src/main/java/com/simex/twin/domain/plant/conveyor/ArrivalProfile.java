package com.simex.twin.domain.plant.conveyor;

/**
 * Ritmo y mezcla de llegadas de piezas.
 *
 * @param meanIntervalS media del tiempo exponencial entre llegadas
 * @param metalRatio probabilidad de que una pieza sea metálica
 */
public record ArrivalProfile(double meanIntervalS, double metalRatio) {}
