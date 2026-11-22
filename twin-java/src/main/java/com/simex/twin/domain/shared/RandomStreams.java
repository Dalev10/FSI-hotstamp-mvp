package com.simex.twin.domain.shared;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Flujos pseudoaleatorios independientes derivados de una semilla. Cada flujo usa
 * {@code SplittableRandom(mix64(seed ^ fnv1a64(nombre)))}, así que agregar un flujo no altera los demás.
 *
 * <p>Estos algoritmos fijan la reproducibilidad de todas las corridas y del golden: no se cambian sin avisar.
 */
public final class RandomStreams {

    /** Flujos del gemelo. El {@code id} es lo que entra al hash. */
    public enum Name {
        ARRIVALS("arrivals"),
        PIECE_TYPE("pieceType"),
        INDUCTIVE_MISS("inductiveMiss"),
        TC_NOISE("tcNoise"),
        AMBIENT_NOISE("ambientNoise");

        private final String id;

        Name(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    // FNV-1a de 64 bits: base y primo del algoritmo.
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int BYTE_MASK = 0xff;

    private final Map<Name, RandomStream> streams = new EnumMap<>(Name.class);

    public RandomStreams(long seed) {
        for (Name name : Name.values()) {
            streams.put(name, new RandomStream(streamSeed(seed, name.id())));
        }
    }

    /** Siempre la misma instancia por nombre: el flujo conserva su estado. */
    public RandomStream get(Name name) {
        return streams.get(name);
    }

    static long streamSeed(long seed, String name) {
        return mix64(seed ^ fnv1a64(name));
    }

    /** Finalizador de SplitMix64 (variante 13 de Stafford), el mismo de {@code SplittableRandom}. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** FNV-1a de 64 bits sobre los bytes UTF-8 del nombre. */
    static long fnv1a64(String name) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & BYTE_MASK;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
