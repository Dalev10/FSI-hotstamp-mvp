package com.simex.twin.domain.plant.conveyor;

/** Pieza sobre la banda. Su posición es la del frente; ocupa {@code [frente − largo, frente]}. */
public final class Piece {

    private final String id;
    private final PieceType type;
    private final double lengthM;
    private double frontM;
    private boolean inductiveMissed;
    private boolean stamped;
    private double heatTakenJ;

    public Piece(String id, PieceType type, double lengthM) {
        this.id = id;
        this.type = type;
        this.lengthM = lengthM;
        this.frontM = lengthM; // entra con la parte trasera en x = 0
    }

    public String id() {
        return id;
    }

    public double frontM() {
        return frontM;
    }

    public double rearM() {
        return frontM - lengthM;
    }

    public double centerM() {
        return frontM - lengthM / 2;
    }

    public boolean overlaps(double xM) {
        return rearM() <= xM && xM <= frontM;
    }

    public boolean isMetal() {
        return type == PieceType.METAL;
    }

    public boolean inductiveMissed() {
        return inductiveMissed;
    }

    public boolean stamped() {
        return stamped;
    }

    public double heatTakenJ() {
        return heatTakenJ;
    }

    void moveBy(double distanceM) {
        frontM += distanceM;
    }

    void setInductiveMissed(boolean missed) {
        inductiveMissed = missed;
    }

    public void markStamped() {
        stamped = true;
    }

    public void takeHeat(double joules) {
        heatTakenJ += joules;
    }
}
