package com.simex.twin.domain.shared;

import java.util.SplittableRandom;

/**
 * Flujo pseudoaleatorio reproducible. De {@link SplittableRandom} solo usa {@code nextLong()}, cuyo algoritmo
 * (SplitMix64) es estable entre versiones del JDK; el uniforme y la gaussiana se derivan aquí.
 */
public final class RandomStream {

    // 53 bits de mantisa: (nextLong >>> 11) · 2⁻⁵³ da un uniforme exacto en [0, 1).
    private static final int UNUSED_BITS = 11;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private final SplittableRandom random;

    RandomStream(long seed) {
        this.random = new SplittableRandom(seed);
    }

    /** Uniforme en [0, 1). */
    public double nextDouble() {
        return (random.nextLong() >>> UNUSED_BITS) * DOUBLE_UNIT;
    }

    /** Normal estándar por Box–Muller; consume dos uniformes por muestra y no guarda la segunda del par. */
    public double nextGaussian() {
        double u1 = 1.0 - nextDouble(); // en (0, 1]: evita log(0)
        double u2 = nextDouble();
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
    }
}
