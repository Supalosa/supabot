package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.spatial.Point2d;

import java.util.*;

public class Ramp implements TileSet {
    private final int rampId;
    private final Set<Point2d> rampTiles;
    private final Set<Point2d> topOfRampTiles;
    private final RampDirection rampDirection;
    private final int rampMidHeight;

    public enum RampDirection {
        NORTH_EAST,
        SOUTH_EAST,
        SOUTH_WEST,
        NORTH_WEST,
        UNKNOWN
    }

    public Ramp(int rampId, Set<Point2d> rampTiles, Set<Point2d> topOfRampTiles, RampDirection rampDirection, int rampMidHeight) {
        this.rampId = rampId;
        this.rampTiles = rampTiles;
        this.topOfRampTiles = topOfRampTiles;
        this.rampDirection = rampDirection;
        this.rampMidHeight = rampMidHeight;
    }

    public int getRampId() {
        return rampId;
    }

    public Set<Point2d> getRampTiles() {
        return rampTiles;
    }

    @Override
    public Set<Point2d> getTiles() {
        return getRampTiles();
    }

    @Override
    public Optional<Set<Point2d>> getBorderTiles() {
        return Optional.empty();
    }

    public Set<Point2d> getTopOfRampTiles() {
        return topOfRampTiles;
    }

    /**
     * Returns the direction of this ramp. The direction is relative to the upwards direction.
     */
    public RampDirection getRampDirection() {
        return rampDirection;
    }

    public int getRampMidHeight() {
        return rampMidHeight;
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

    public Point2d calculateMidpoint() {
        double averageRampX = rampTiles.stream()
                .mapToDouble(point -> point.getX()).average().orElse(Double.NaN);
        double averageRampY = rampTiles.stream()
                .mapToDouble(point -> point.getY()).average().orElse(Double.NaN);
        return Point2d.of((float)averageRampX, (float)averageRampY);
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

    private static final Point2d[] FOUR_NEIGHBOURS = new Point2d[]{
            Point2d.of(-1, 0),
            Point2d.of(1, 0),
            Point2d.of(0,-1),
            Point2d.of(0, 1)
    };

    /**
     * Returns true if this ramp is blocked, that is, no path exists from a highground to a lowground tile.
     * This is a relatively expensive, uncached method, do not call too frequently.
     */
    public boolean calculateIsBlocked(ObservationInterface observation) {
        if (rampTiles.size() == 0) {
            return false;
        }
        List<Point2d> tiles = new ArrayList<>();
        tiles.add(rampTiles.stream().findFirst().get());
        Set<Point2d> visited = new HashSet<>();
        while (tiles.size() > 0) {
            Point2d head = tiles.remove(0);
            visited.add(head);
            // note, use 4-neighbour not 8-neighbour for correctness.
            for (int i = 0; i < FOUR_NEIGHBOURS.length; ++i) {
                Point2d neighbourPoint = head.add(FOUR_NEIGHBOURS[i]);
                if (rampTiles.contains(neighbourPoint) && !visited.contains(neighbourPoint)) {
                    visited.add(neighbourPoint);
                    if (observation.isPathable(neighbourPoint)) {
                        tiles.add(neighbourPoint);
                    }
                }
            }
        }
        // If we visited all the tiles then it's unblocked.
        if (visited.size() != rampTiles.size()) {
            int i = 1;
            ++i;
        }
        return visited.size() != rampTiles.size();
    }
}
