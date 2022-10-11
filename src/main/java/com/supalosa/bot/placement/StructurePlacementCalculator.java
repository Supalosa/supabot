package com.supalosa.bot.placement;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.data.UnitTypeData;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Ramp;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;
import com.supalosa.bot.pathfinding.BreadthFirstSearch;
import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Calculates where to place walls for different races.
 */
public class StructurePlacementCalculator {

    private final AnalysisResults mapAnalysisResult;
    private final Point2d start;
    private final GameData gameData;

    private Optional<Optional<Ramp>> mainRamp = Optional.empty();

    private Optional<Tag> firstSupplyDepotTag = Optional.empty();
    private Optional<Tag> secondSupplyDepotTag = Optional.empty();

    private Optional<Optional<Point2d>> firstSupplyDepotLocation = Optional.empty();
    private Optional<Optional<Point2d>> secondSupplyDepotLocation = Optional.empty();
    private Optional<Optional<Point2d>> barracksLocation = Optional.empty();

    private List<Point2d> myStructures = new ArrayList<>();
    private long myStructuresUpdatedAt = 0L;

    // Grid of tiles available for free placement (i.e. not reserved tiles).
    private final Grid<Boolean> freePlacementGrid;

    public StructurePlacementCalculator(AnalysisResults mapAnalysisResult, GameData gameData, Point2d start) {
        this.mapAnalysisResult = mapAnalysisResult;
        this.gameData = gameData;
        this.start = start;
        this.freePlacementGrid = InMemoryGrid.copyOf(
                Boolean.class,
                mapAnalysisResult.getGrid(), () -> false, tile -> tile.placeable);
    }

    void updateFreePlacementGrid() {
        if (firstSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = firstSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint((int)location.getX(), (int)location.getY(), 2, 2);
            });
        }
        if (secondSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = secondSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint((int)location.getX(), (int)location.getY(), 2, 2);
            });
        }
        if (barracksLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = barracksLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint((int)location.getX(), (int)location.getY(), 3, 3);
            });
        }
    }

    // This is different from a square, it is related to the footprint of the building.
    void updatePlacementGridWithFootprint(int x, int y, int w, int h) {
        int xStart = (int)Math.ceil(x - w / 2);
        int yStart = (int)Math.ceil(y - h / 2);
        for (int xx = xStart; xx < xStart + w; ++xx) {
            for (int yy = yStart; yy < yStart + h; ++yy) {
                freePlacementGrid.set(xx, yy, false);
            }
        }
    }
    void updatePlacementGridWithRectangle(int x, int y, int w, int h) {
        for (int xx = x; xx < x + w; ++xx) {
            for (int yy = y; yy < y + h; ++yy) {
                freePlacementGrid.set(xx, yy, false);
            }
        }
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
            updateFreePlacementGrid();
        }
        return firstSupplyDepotLocation.get();
    }

    public Optional<Point2d> getSecondSupplyDepotLocation() {
        if (secondSupplyDepotLocation.isEmpty()) {
            secondSupplyDepotLocation = Optional.of(calculateNthSupplyDepotLocation(start, 1));
            updateFreePlacementGrid();
        }
        return secondSupplyDepotLocation.get();
    }

    public Optional<Point2d> getFirstBarracksLocation(Point2d start) {
        if (barracksLocation.isEmpty()) {
            barracksLocation = Optional.of(calculateBarracksLocation(start));
            updateFreePlacementGrid();
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

    public void debug(S2Agent agent) {
        Point2d cameraCenter = agent.observation().getCameraPos().toPoint2d();
        int minX = Math.max(1, (int)cameraCenter.getX() - 20);
        int maxX = Math.min((int)cameraCenter.getX() + 20, freePlacementGrid.getWidth() - 1);
        int minY = Math.max(1, (int)cameraCenter.getY() - 20);
        int maxY = Math.min((int)cameraCenter.getY() + 20, freePlacementGrid.getHeight() - 1);
        for (int x = minX; x < maxX; ++x) {
            for (int y = minY; y < maxY; ++y) {
                Point2d point2d = Point2d.of(x, y);
                float height = agent.observation().terrainHeight(point2d) + 0.05f;
                Point point3d = Point.of(x, y, height);
                if (freePlacementGrid.get(x, y) == false) {
                    agent.debug().debugBoxOut(
                            point3d.sub(-0.05f, -0.05f, 0f),
                            point3d.sub(-0.95f, -0.95f, 0f), Color.GRAY);
                }
            }
        }
    }

    public void handleExpansions(List<Expansion> expansionLocations) {
        expansionLocations.forEach(expansion -> {
           updatePlacementGridWithFootprint((int)expansion.position().getX(), (int)expansion.position().getY(), 5, 5);
            // reserve points between the expansion and the resource.
            // Just a few points should be enough to stop most structures being placed here.
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (Point2d resourcePosition : expansion.resourcePositions()) {
                if (resourcePosition.getX() < minX) {
                    minX = (int)resourcePosition.getX();
                }
                if (resourcePosition.getY() < minY) {
                    minY = (int)resourcePosition.getY();
                }
                if (resourcePosition.getX() > maxX) {
                    maxX = (int)resourcePosition.getX();
                }
                if (resourcePosition.getY() > maxY) {
                    maxY = (int)resourcePosition.getY();
                }
            }
            if (expansion.resourcePositions().size() > 0) {
                updatePlacementGridWithRectangle(minX, minY, maxX - minX, maxY - minY);
            }
        });
    }

    private static final int MAX_FREE_PLACEMENT_ITERATIONS = 20;

    // does NOT query for placement but will never suggest something that overlaps with a reserved tile.
    public Optional<Point2d> suggestLocationForFreePlacement(Point2d origin, int searchRadius, int structureWidth, int structureHeight) {
        List<Point2d> nearbyStructures = myStructures.stream().filter(myStructurePoint2d -> {
            return myStructurePoint2d.distance(origin) < searchRadius;
        }).collect(Collectors.toList());
        for (int i = 0; i < MAX_FREE_PLACEMENT_ITERATIONS; ++i) {
            // prefer to place next to an existing structure but swapping on alternating queries
            Point2d candidate;
            if (nearbyStructures.size() > 0 && i % 2 == 0) {
                candidate = nearbyStructures.get(getRandomInteger(0, nearbyStructures.size()))
                        .add((structureWidth + 1) * getRandomSign(), (structureHeight + 1) * getRandomSign());
            } else {
                candidate = origin.add(
                        Point2d.of(
                                getRandomInteger(-searchRadius, searchRadius),
                                getRandomInteger(-searchRadius, searchRadius)));
            }
            if (canPlaceAt(candidate, structureWidth, structureWidth)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public Optional<Point2d> suggestLocationForFreePlacement(Point2d position, int searchRadius, Ability ability) {
        // assume the worst for radius = 5x5.
        float radius = gameData.getAbilityRadius(ability).orElse(2.5f);
        int width = (int)(radius * 2);
        int height = (int)(radius * 2);
        return suggestLocationForFreePlacement(position, searchRadius, width, height);
    }

    private boolean canPlaceAt(Point2d origin, int width, int height) {
        int x = (int)origin.getX();
        int y = (int)origin.getY();
        int xStart = (int)Math.ceil(x - width / 2);
        int yStart = (int)Math.ceil(y - height / 2);
        for (int xx = xStart; xx < xStart + width; ++xx) {
            for (int yy = yStart; yy < yStart + height; ++yy) {
                if (freePlacementGrid.get(xx, yy) == false) {
                    return false;
                }
            }
        }
        return true;
    }

    private int getRandomInteger(int origin, int bound) {
        return ThreadLocalRandom.current().nextInt(origin, bound);
    }
    private int getRandomSign() {
        return ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
    }

    private static final long MY_STRUCTURE_UPDATE_INTERVAL = 22L * 10;

    public void onStep(AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > myStructuresUpdatedAt + MY_STRUCTURE_UPDATE_INTERVAL) {
            myStructuresUpdatedAt = gameLoop;
            myStructures = agent.observation().getUnits(unitInPool -> {
                if (unitInPool.unit().getAlliance() != Alliance.SELF) {
                    return false;
                }
                Optional<UnitTypeData> unitTypeData = data.gameData().getUnitTypeData(unitInPool.unit().getType());
                return unitTypeData.map(unitType ->
                    unitType.getAttributes().contains(UnitAttribute.STRUCTURE)
                ).orElse(false);
            }).stream().map(unitInPool -> unitInPool.unit().getPosition().toPoint2d()).collect(Collectors.toList());
        }
    }
}
