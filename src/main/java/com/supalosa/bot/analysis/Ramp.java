package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;

import java.util.Set;

public class Ramp {
    private final int rampId;
    private final Set<Point2d> rampTiles;
    private final Set<Point2d> topOfRampTiles;
    private final RampDirection rampDirection;

    public enum RampDirection {
        NORTH_EAST,
        SOUTH_EAST,
        SOUTH_WEST,
        NORTH_WEST,
        UNKNOWN
    }

    public Ramp(int rampId, Set<Point2d> rampTiles, Set<Point2d> topOfRampTiles, RampDirection rampDirection) {
        this.rampId = rampId;
        this.rampTiles = rampTiles;
        this.topOfRampTiles = topOfRampTiles;
        this.rampDirection = rampDirection;
    }

    public int getRampId() {
        return rampId;
    }

    public Set<Point2d> getRampTiles() {
        return rampTiles;
    }

    public Set<Point2d> getTopOfRampTiles() {
        return topOfRampTiles;
    }

    public RampDirection getRampDirection() {
        return rampDirection;
    }

    public static RampDirection calculateDirection(Set<Point2d> rampPoints, Set<Point2d> topOfRampPoints) {
        double averageRampX = rampPoints.stream()
                .mapToDouble(point -> point.getX()).average().orElse(Double.NaN);
        double averageRampY = rampPoints.stream()
                .mapToDouble(point -> point.getY()).average().orElse(Double.NaN);
        double averageTopOfRampX = topOfRampPoints.stream()
                .mapToDouble(point -> point.getX()).average().orElse(Double.NaN);
        double averageTopOfRampY = topOfRampPoints.stream()
                .mapToDouble(point -> point.getY()).average().orElse(Double.NaN);
        final double threshold = 1.0;
        if (averageTopOfRampX > averageRampX + threshold) {
            if (averageTopOfRampY < averageRampY - threshold) {
                return RampDirection.SOUTH_EAST;
            } else if (averageTopOfRampY > averageRampY + threshold) {
                return RampDirection.NORTH_EAST;
            }
        } else if (averageTopOfRampX < averageRampX - threshold){
            if (averageTopOfRampY < averageRampY - threshold) {
                return RampDirection.SOUTH_WEST;
            } else if (averageTopOfRampY > averageRampY + threshold) {
                return RampDirection.NORTH_WEST;
            }
        }
        return RampDirection.UNKNOWN;
    }
}
