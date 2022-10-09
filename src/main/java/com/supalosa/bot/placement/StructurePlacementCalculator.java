package com.supalosa.bot.placement;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Ramp;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.pathfinding.BreadthFirstSearch;
import org.apache.commons.lang3.NotImplementedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Calculates where to place walls for different races.
 */
public class StructurePlacementCalculator {

    private final AnalysisResults mapAnalysisResult;
    private final Point2d start;

    private Optional<Optional<Ramp>> mainRamp = Optional.empty();

    private Optional<Tag> firstSupplyDepotTag = Optional.empty();
    private Optional<Tag> secondSupplyDepotTag = Optional.empty();

    private Optional<Optional<Point2d>> firstSupplyDepotLocation = Optional.empty();
    private Optional<Optional<Point2d>> secondSupplyDepotLocation = Optional.empty();
    private Optional<Optional<Point2d>> barracksLocation = Optional.empty();

    public StructurePlacementCalculator(AnalysisResults mapAnalysisResult, Point2d start) {
        this.mapAnalysisResult = mapAnalysisResult;
        this.start = start;
    }

    /**
     * Returns the actual supply depot that (should be) at the first location on the main ramp.
     * @param observation Observation to query.
     * @return
     */
    public Optional<UnitInPool> getFirstSupplyDepot(ObservationInterface observation) {
        if (firstSupplyDepotTag.isPresent()) {
            UnitInPool unit = observation.getUnit(firstSupplyDepotTag.get());
            if (unit != null && unit.isAlive()) {
                return Optional.of(unit);
            } else {
                firstSupplyDepotTag = Optional.empty();
            }
        }
        Optional<Point2d> supplyDepotLocation = getFirstSupplyDepotLocation();
        if (supplyDepotLocation.isEmpty()) {
            return Optional.empty();
        }
        Optional<UnitInPool> supplyDepot = getSupplyDepotAtLocation(observation, supplyDepotLocation.get());
        firstSupplyDepotTag = supplyDepot.map(unitInPool -> unitInPool.getTag());
        return supplyDepot;
    }

    public Optional<UnitInPool> getSecondSupplyDepot(ObservationInterface observation) {
        if (secondSupplyDepotTag.isPresent()) {
            UnitInPool unit = observation.getUnit(secondSupplyDepotTag.get());
            if (unit != null && unit.isAlive()) {
                return Optional.of(unit);
            } else {
                secondSupplyDepotTag = Optional.empty();
            }
        }
        Optional<Point2d> supplyDepotLocation = getSecondSupplyDepotLocation();
        if (supplyDepotLocation.isEmpty()) {
            return Optional.empty();
        }
        Optional<UnitInPool> supplyDepot = getSupplyDepotAtLocation(observation, supplyDepotLocation.get());
        secondSupplyDepotTag = supplyDepot.map(unitInPool -> unitInPool.getTag());
        return supplyDepot;
    }
    Optional<UnitInPool> getSupplyDepotAtLocation(ObservationInterface observation, Point2d point) {
        List<UnitInPool> supplyDepotInLocation = observation.getUnits(Alliance.SELF,
                unitInPool -> isSupplyDepot(unitInPool) &&
                        unitInPool.unit().getPosition().toPoint2d().equals(point));
        if (supplyDepotInLocation.size() == 0) {
            return Optional.empty();
        } else {
            return Optional.of(supplyDepotInLocation.get(0));
        }
    }

    boolean isSupplyDepot(UnitInPool unitInPool) {
        return unitInPool.isAlive() &&
                (unitInPool.unit().getType() == Units.TERRAN_SUPPLY_DEPOT ||
                 unitInPool.unit().getType() == Units.TERRAN_SUPPLY_DEPOT_LOWERED);
    }

    public Optional<Point2d> getFirstSupplyDepotLocation() {
        if (firstSupplyDepotLocation.isEmpty()) {
            firstSupplyDepotLocation = Optional.of(calculateNthSupplyDepotLocation(start, 0));
        }
        return firstSupplyDepotLocation.get();
    }

    public Optional<Point2d> getSecondSupplyDepotLocation() {
        if (secondSupplyDepotLocation.isEmpty()) {
            secondSupplyDepotLocation = Optional.of(calculateNthSupplyDepotLocation(start, 1));
        }
        return secondSupplyDepotLocation.get();
    }

    public Optional<Point2d> getFirstBarracksLocation(Point2d start) {
        if (barracksLocation.isEmpty()) {
            barracksLocation = Optional.of(calculateBarracksLocation(start));
        }
        return barracksLocation.get();
    }

    Optional<Ramp> getRamp(Point2d start) {
        // Find the first ramp tile from the start.
        Grid<Tile> mapGrid = mapAnalysisResult.getGrid();
        Optional<Point2d> maybeNearestRamp = BreadthFirstSearch.getFirstPoint(start, mapGrid, tile -> tile.isRamp);
        if (maybeNearestRamp.isEmpty()) {
            // TODO: implement fallback if no ramp.
            return Optional.empty();
        }
        Point2d nearestRamp = maybeNearestRamp.get();
        Tile rampTile = mapGrid.get((int) nearestRamp.getX(), (int) nearestRamp.getY());
        //System.out.println("We found ramp at " + maybeNearestRamp.get() + " with ID " + rampTile.rampId);
        Ramp theRamp = mapAnalysisResult.getRamp(rampTile.rampId);
        return Optional.of(theRamp);
    }

    Optional<Point2d> calculateBarracksLocation(Point2d start) {
        Optional<Ramp> maybeRamp = getRamp(start);
        //System.out.println("The ramp has " + theRamp.getRampTiles().size() + " tiles and " + theRamp.getTopOfRampTiles().size() + " top of ramp tiles");
        //System.out.println("The ramp is pointing " + theRamp.getRampDirection().name());
        if (maybeRamp.isEmpty()) {
            return Optional.empty();
        }
        Ramp ramp = maybeRamp.get();
        // we only handle standard 7-top-tile with known direction at this time
        if (ramp.getRampDirection() == Ramp.RampDirection.UNKNOWN) {
            return Optional.empty();
        }
        if (ramp.getTopOfRampTiles().size() != 7) {
            return Optional.empty();
        }
        // TODO: check the shape of the top of the ramp complies with the standard.
        Point2d northTile = getNorthmostTile(ramp.getTopOfRampTiles());
        Point2d southTile = getSouthmostTile(ramp.getTopOfRampTiles());
        // Origin of 2x2 buildings seems to be the northeast tile.
        switch (ramp.getRampDirection()) {
            case SOUTH_WEST:
            case SOUTH_EAST:
                return Optional.of(northTile.add(0, -3));
            case NORTH_EAST:
            case NORTH_WEST:
                return Optional.of(southTile.add(0, 3));
        }
        return Optional.empty();
    }

    Optional<Point2d> calculateNthSupplyDepotLocation(Point2d start, int n) {
        if (n < 0 || n > 1) {
            throw new NotImplementedException("Only support getting first and second supply depot location");
        }
        Optional<Ramp> maybeRamp = getRamp(start);
        //System.out.println("The ramp has " + theRamp.getRampTiles().size() + " tiles and " + theRamp.getTopOfRampTiles().size() + " top of ramp tiles");
        //System.out.println("The ramp is pointing " + theRamp.getRampDirection().name());
        if (maybeRamp.isEmpty()) {
            return Optional.empty();
        }
        Ramp ramp = maybeRamp.get();
        // we only handle standard 7-top-tile with known direction at this time
        if (ramp.getRampDirection() == Ramp.RampDirection.UNKNOWN) {
            return Optional.empty();
        }
        if (ramp.getTopOfRampTiles().size() != 7) {
            return Optional.empty();
        }
        // TODO: check the shape of the top of the ramp complies with the standard.
        Point2d northTile = getNorthmostTile(ramp.getTopOfRampTiles());
        Point2d southTile = getSouthmostTile(ramp.getTopOfRampTiles());
        // Origin of 2x2 buildings seems to be the northeast tile.
        if (n == 0) {
            switch (ramp.getRampDirection()) {
                case SOUTH_WEST:
                    return Optional.of(northTile);
                case SOUTH_EAST:
                    return Optional.of(northTile.add(1, 0));
                case NORTH_WEST:
                    return Optional.of(southTile.add(0, 1));
                case NORTH_EAST:
                    return Optional.of(southTile.add(1, 1));
            }
        } else if (n == 1) {
            switch (ramp.getRampDirection()) {
                case SOUTH_WEST:
                    return Optional.of(northTile.add(0+3, 0-3));
                case SOUTH_EAST:
                    return Optional.of(northTile.add(1-3, 0-3));
                case NORTH_WEST:
                    return Optional.of(southTile.add(0+3, 1+3));
                case NORTH_EAST:
                    return Optional.of(southTile.add(1-3, 1+3));
            }
        }
        return Optional.empty();
    }

    Point2d getSouthmostTile(Set<Point2d> points) {
        // Note: decreasing Y = south
        int minY = Integer.MAX_VALUE;
        Point2d minPoint = null;
        for (Point2d point : points) {
            if (point.getY() < minY) {
                minY = (int)point.getY();
                minPoint = point;
            }
        }
        return minPoint;
    }

    Point2d getNorthmostTile(Set<Point2d> points) {
        // Note: increasing Y = north.
        int maxY = Integer.MIN_VALUE;
        Point2d maxPoint = null;
        for (Point2d point : points) {
            if (point.getY() > maxY) {
                maxY = (int)point.getY();
                maxPoint = point;
            }
        }
        return maxPoint;
    }
}
