package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;

import java.util.Optional;
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

    /**
     * Project a point along the direction of the ramp from the centre.
     * A higher number will be towards the high ground, a lower number will be towards the low ground.
     *
     * @param distance Distance in tiles to project along the direction.
     * @return A point along the ramp's direction, or absent if the direction is unknown.
     */
    public Optional<Point2d> projection(float distance) {
        double averageRampX = rampTiles.stream()
                .mapToDouble(point -> point.getX()).average().orElse(Double.NaN);
        double averageRampY = rampTiles.stream()
                .mapToDouble(point -> point.getY()).average().orElse(Double.NaN);
        Point2d averagePosition = Point2d.of((float)averageRampX, (float)averageRampY);
        switch (rampDirection) {
            case NORTH_EAST:
                return Optional.of(averagePosition.add(distance, distance));
            case NORTH_WEST:
                return Optional.of(averagePosition.add(-distance, distance));
            case SOUTH_EAST:
                return Optional.of(averagePosition.add(distance, -distance));
            case SOUTH_WEST:
                return Optional.of(averagePosition.add(-distance, -distance));
            case UNKNOWN:
            default:
                return Optional.empty();
        }
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
