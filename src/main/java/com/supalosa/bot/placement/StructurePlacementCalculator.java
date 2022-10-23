package com.supalosa.bot.placement;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Ramp;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;
import com.supalosa.bot.pathfinding.BreadthFirstSearch;
import com.supalosa.bot.task.PlacementRules;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;

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
    private Optional<Optional<Point2d>> barracksWithAddonLocation = Optional.empty();

    private List<Point2d> myStructures = new ArrayList<>();
    private long myStructuresUpdatedAt = 0L;

    // Grid of tiles available for free placement (i.e. not reserved tiles).
    // This grid is STATIC and should not be modified during the game.
    private final Grid<Boolean> staticFreePlacementGrid;
    // This grid can (and should) be modified to mark tiles that should not be built on.
    private final Grid<Boolean> mutableFreePlacementGrid;

    public StructurePlacementCalculator(AnalysisResults mapAnalysisResult, GameData gameData, Point2d start) {
        this.mapAnalysisResult = mapAnalysisResult;
        this.gameData = gameData;
        this.start = start;
        this.staticFreePlacementGrid = InMemoryGrid.copyOf(
                Boolean.class,
                mapAnalysisResult.getGrid(), () -> true, tile -> tile.placeable);
        this.mutableFreePlacementGrid = new InMemoryGrid<>(
                Boolean.class,
                mapAnalysisResult.getGrid().getWidth(),
                mapAnalysisResult.getGrid().getHeight(),
                () -> true);
    }

    void updateFreePlacementGrid() {
        if (firstSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = firstSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 2, 2);
            });
        }
        if (secondSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = secondSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 2, 2);
            });
        }
        if (barracksWithAddonLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = barracksWithAddonLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 3, 3);
            });
        }
    }

    /**
     * Marks the tiles under a structure positioned at (x,y) with width (w,h) as unplaceable.
     */
    void updatePlacementGridWithFootprint(Grid<Boolean> grid, int x, int y, int w, int h) {
        // Note that a structure's origin is at its centre (biased to northeast for even numbers, hence the `ceil`)
        int xStart = (int)Math.ceil(x - w / 2);
        int yStart = (int)Math.ceil(y - h / 2);
        for (int xx = xStart; xx < xStart + w; ++xx) {
            for (int yy = yStart; yy < yStart + h; ++yy) {
                if (grid.isInBounds(xx, yy)) {
                    grid.set(xx, yy, false);
                }
            }
        }
    }

    /**
     * Marks the tiles from (x,y) to (x+w,y+h) as unplaceable.
     */
    void updatePlacementGridWithRectangle(Grid<Boolean> grid, int x, int y, int w, int h) {
        for (int xx = x; xx < x + w; ++xx) {
            for (int yy = y; yy < y + h; ++yy) {
                grid.set(xx, yy, false);
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

    public Optional<Point2d> getFirstBarracksWithAddonLocation() {
        if (barracksWithAddonLocation.isEmpty()) {
            barracksWithAddonLocation = Optional.of(calculateBarracksWithAddonLocation(start));
            updateFreePlacementGrid();
        }
        return barracksWithAddonLocation.get();
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

    Optional<Point2d> calculateBarracksWithAddonLocation(Point2d start) {
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
        // West-facing ramps need to be shifted to fit the addon. Unfortunately it leaves a gap in the wall.
        switch (ramp.getRampDirection()) {
            case SOUTH_EAST:
                return Optional.of(northTile.add(0, -3));
            case SOUTH_WEST:
                return Optional.of(northTile.add(-2, -3));
            case NORTH_EAST:
                return Optional.of(southTile.add(0, 3));
            case NORTH_WEST:
                return Optional.of(southTile.add(-2, 3));
        }
        return Optional.empty();
    }

    /**
     * Returns the main ramp. Calculates it if it hasn't been calculated before.
     * @param start The start position of the bot.
     * @return A main ramp reference, or empty if not found.
     */
    public Optional<Ramp> getMainRamp(Point2d start) {
        if (this.mainRamp.isEmpty()) {
            Optional<Ramp> maybeRamp = getRamp(start);
            if (maybeRamp.isEmpty()) {
                return Optional.empty();
            }
            this.mainRamp = Optional.of(maybeRamp);
        }
        return this.mainRamp.get();
    }

    /**
     * Returns the pre-calculated main ramp.
     * @return The main ramp reference. Empty if not or if it has not been calculated.
     */
    public Optional<Ramp> getMainRamp() {
        return this.mainRamp.flatMap(ramp -> ramp);
    }

    Optional<Point2d> calculateNthSupplyDepotLocation(Point2d start, int n) {
        if (n < 0 || n > 1) {
            throw new NotImplementedException("Only support getting first and second supply depot location");
        }
        Optional<Ramp> maybeRamp = getMainRamp(start);
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
        int maxX = Math.min((int)cameraCenter.getX() + 20, staticFreePlacementGrid.getWidth() - 1);
        int minY = Math.max(1, (int)cameraCenter.getY() - 20);
        int maxY = Math.min((int)cameraCenter.getY() + 20, staticFreePlacementGrid.getHeight() - 1);
        for (int x = minX; x < maxX; ++x) {
            for (int y = minY; y < maxY; ++y) {
                Point2d point2d = Point2d.of(x, y);
                float height = agent.observation().terrainHeight(point2d) + 0.05f;
                Point point3d = Point.of(x, y, height);
                if (staticFreePlacementGrid.get(x, y) == false) {
                    agent.debug().debugBoxOut(
                            point3d.sub(-0.05f, -0.05f, 0.1f),
                            point3d.sub(-0.95f, -0.95f, -0.1f), Color.GRAY);
                }
                if (mutableFreePlacementGrid.get(x, y) == false) {
                    agent.debug().debugBoxOut(
                            point3d.sub(-0.1f, -0.1f, 0.2f),
                            point3d.sub(-0.9f, -0.9f, -0.2f), Color.of(156, 156, 156));
                }
                /*int width = 2;
                int h = 2;
                int xStart = (int)Math.ceil(x - width / 2);
                int yStart = (int)Math.ceil(y - h / 2);
                if (canPlaceAt(point2d, 2, 2)) {
                    Point p1 = Point.of(xStart, yStart, height);
                    Point p2 = Point.of(xStart + width, yStart + h, height);
                    agent.debug().debugBoxOut(
                            p1,
                            p2, Color.GREEN);
                }*/
            }
        }
    }

    public void onExpansionsCalculated(List<Expansion> expansionLocations) {
        // Block out the town hall position and the space between the resources and the town hall to prevent
        // blockage.
        expansionLocations.forEach(expansion -> {
           updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)expansion.position().getX(), (int)expansion.position().getY(), 5, 5);
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
                updatePlacementGridWithRectangle(staticFreePlacementGrid, minX, minY, maxX - minX, maxY - minY);
                expansion.resourcePositions().forEach(position -> {
                   // TODO: use the correct footprint for the resource.
                   updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)position.getX(), (int)position.getY(), 3, 3);
                });
            }
        });
    }

    // does NOT query for placement but will never suggest something that overlaps with a reserved tile.
    public Optional<Point2d> suggestLocationForFreePlacement(AgentData data,
                                                             Point2d origin,
                                                             int searchRadius,
                                                             int structureWidth,
                                                             int structureHeight,
                                                             Optional<PlacementRules> placementRules) {
        int actualSearchRadius = placementRules.map(PlacementRules::maxVariation).orElse(searchRadius);
        PlacementRules.Region region = placementRules
                .map(PlacementRules::regionType)
                .orElse(PlacementRules.Region.ANY_PLAYER_BASE);
        // Reserve the location after we suggest it. It will be wiped clear in the next structure grid update if
        // it doesn't actually get used.
        Optional<Point2d> suggestedLocation = Optional.empty();
        switch (region) {
            case ANY_PLAYER_BASE:
                suggestedLocation = findAnyPlacement(origin, actualSearchRadius, structureWidth, structureHeight);
                break;
            case EXPANSION:
                suggestedLocation = findExpansionPlacement(origin, structureWidth, structureHeight, data);
                break;
            case MAIN_RAMP_SUPPLY_DEPOT_1:
                suggestedLocation = getFirstSupplyDepotLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules))
                        .orElseGet(() -> findAnyPlacement(origin, actualSearchRadius, structureWidth, structureHeight));
                break;
            case MAIN_RAMP_SUPPLY_DEPOT_2:
                suggestedLocation = getSecondSupplyDepotLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules))
                        .orElseGet(() -> findAnyPlacement(origin, actualSearchRadius, structureWidth, structureHeight));
                break;
            case MAIN_RAMP_BARRACKS_WITH_ADDON:
                suggestedLocation = getFirstBarracksWithAddonLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules))
                        .orElseGet(() -> findAnyPlacement(origin, actualSearchRadius, structureWidth, structureHeight));
                break;
            default:
                throw new IllegalArgumentException("Unsupported placement region logic: " + region);
        }
        if (suggestedLocation.isPresent()) {
            updatePlacementGridWithFootprint(mutableFreePlacementGrid,
                    (int)suggestedLocation.get().getX(),
                    (int)suggestedLocation.get().getY(),
                    structureWidth,
                    structureHeight);
        }
        return suggestedLocation;
    }

    private static final int MAX_FREE_PLACEMENT_ITERATIONS = 40;
    private static final int MAX_ADJACENT_PLACEMENT_ITERATIONS = 10;

    private Optional<Point2d> findAnyPlacement(Point2d origin, int actualSearchRadius, int structureWidth, int structureHeight) {
        List<Point2d> nearbyStructures = myStructures.stream().filter(myStructurePoint2d -> {
            return myStructurePoint2d.distance(origin) < actualSearchRadius;
        }).collect(Collectors.toList());
        for (int i = 0; i < MAX_FREE_PLACEMENT_ITERATIONS; ++i) {
            // prefer to place next to an existing structure.
            Point2d candidate;
            if (nearbyStructures.size() > 0 && i < MAX_ADJACENT_PLACEMENT_ITERATIONS) {
                if (ThreadLocalRandom.current().nextBoolean()) {
                    // up-down
                    candidate = nearbyStructures.get(getRandomInteger(0, nearbyStructures.size()))
                            .add(0, (structureHeight) * getRandomSign());
                } else {
                    // left-right
                    candidate = nearbyStructures.get(getRandomInteger(0, nearbyStructures.size()))
                            .add((structureWidth) * getRandomSign(), 0);
                }
            } else {
                // Initially we will search closer to the querying worker. The longer we search, the more willing we
                // are to place something far away.
                int testedRadius = (int)Math.max(1, actualSearchRadius * Math.min(1f, (float)i / (float)MAX_FREE_PLACEMENT_ITERATIONS));
                candidate = origin.add(
                        Point2d.of(
                                getRandomInteger(-testedRadius, testedRadius),
                                getRandomInteger(-testedRadius, testedRadius)));
            }
            candidate = Point2d.of(Math.max(0f, candidate.getX()), Math.max(0f, candidate.getY()));
            if (canPlaceAt(candidate, structureWidth, structureWidth)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<Point2d> findExpansionPlacement(Point2d origin, int structureWidth, int structureHeight, AgentData data) {
        if (data.mapAwareness().getValidExpansionLocations().isEmpty()) {
            return Optional.empty();
        }
        for (Expansion validExpansionLocation : data.mapAwareness().getValidExpansionLocations().get()) {
            Point2d candidate = validExpansionLocation.position().toPoint2d();
            if (canPlaceAtIgnoringStaticGrid(candidate, structureWidth, structureHeight)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Use for when we calculated a position ahead of time.
     */
    private Optional<Point2d> checkSpecificPlacement(Point2d point,
                                                     int structureWidth,
                                                     int structureHeight,
                                                     Optional<PlacementRules> placementRules) {
        if (canPlaceAtIgnoringStaticGrid(point, structureWidth, structureHeight)) {
            return Optional.of(point);
        } else if (placementRules.isPresent() && placementRules.get().maxVariation() > 0) {
            // Place near the target point.
            return findAnyPlacement(point, placementRules.get().maxVariation(), structureWidth, structureHeight);
        }
        return Optional.empty();
    }

    public Optional<Point2d> suggestLocationForFreePlacement(AgentData data,
                                                             Point2d position,
                                                             int searchRadius,
                                                             Ability ability,
                                                             UnitType unitType,
                                                             Optional<PlacementRules> placementRules) {
        // assume the worst for radius = 5x5.
        float radius = gameData.getAbilityRadius(ability).orElse(2.5f);
        int width = (int)(radius * 2);
        int height = (int)(radius * 2);
        Pair<Point2d, Point2d> modifiedFootprint = modifyFootprint(
                Point2d.of(0, 0),
                Point2d.of(radius * 2f, radius * 2f),
                unitType);
        Point2d outputOffset = modifiedFootprint.getLeft();
        Point2d newFootprint = modifiedFootprint.getRight();
        return suggestLocationForFreePlacement(data, position, searchRadius,
                (int)newFootprint.getX(),
                (int)newFootprint.getY(),
                placementRules).map(outputPosition ->
                outputPosition/*.add(outputOffset)*/);
    }

    private boolean canPlaceAt(Point2d origin, int width, int height) {
        return canPlaceAt(origin, width, height, true);
    }

    private boolean canPlaceAtIgnoringStaticGrid(Point2d origin, int width, int height) {
        return canPlaceAt(origin, width, height, false);
    }

    private boolean canPlaceAt(Point2d origin, int width, int height, boolean checkStatic) {
        int x = (int)origin.getX();
        int y = (int)origin.getY();
        int xStart = (int)Math.ceil(x - width / 2);
        int yStart = (int)Math.ceil(y - height / 2);
        for (int xx = xStart; xx < xStart + width; ++xx) {
            for (int yy = yStart; yy < yStart + height; ++yy) {
                if (checkStatic && staticFreePlacementGrid.get(xx, yy) == false) {
                    return false;
                }
                if (mutableFreePlacementGrid.get(xx, yy) == false) {
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

    private static final long MY_STRUCTURE_UPDATE_INTERVAL = 22L * 2;

    private void updateMutableGridForStructure(AgentData data, Point2d position, UnitType unitType) {
        Optional<Point2d> maybeFootprint = data.gameData().getUnitFootprint(unitType);
        maybeFootprint.ifPresent(footprint -> {
            Pair<Point2d, Point2d> modifiedFootprint = modifyFootprint(
                    position,
                    footprint,
                    unitType);
            int x = (int)modifiedFootprint.getLeft().getX();
            int y = (int)modifiedFootprint.getLeft().getY();
            int width = (int)(modifiedFootprint.getRight().getX());
            int height = (int)(modifiedFootprint.getRight().getY());
            updatePlacementGridWithFootprint(mutableFreePlacementGrid, x, y, width, height);
        });
    }

    public void onStep(AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > myStructuresUpdatedAt + MY_STRUCTURE_UPDATE_INTERVAL) {
            myStructuresUpdatedAt = gameLoop;
            mutableFreePlacementGrid.clear();
            // Look at all placed structures.
            myStructures = agent.observation().getUnits(unitInPool -> {
                if (unitInPool.unit().getAlliance() != Alliance.SELF) {
                    return false;
                }

                updateMutableGridForStructure(
                        data,
                        unitInPool.unit().getPosition().toPoint2d(),
                        unitInPool.unit().getType());

                Optional<UnitTypeData> maybeUnitTypeData = data.gameData().getUnitTypeData(unitInPool.unit().getType());
                return maybeUnitTypeData.map(unitType ->
                    unitType.getAttributes().contains(UnitAttribute.STRUCTURE)
                ).orElse(false);
            }).stream().map(unitInPool -> unitInPool.unit().getPosition().toPoint2d()).collect(Collectors.toList());

            // Look at structures being placed but not existing yet.
            agent.observation().getUnits(unitInPool -> unitInPool.unit().getOrders().size() > 0).forEach(unitWithOrder -> {
                List<UnitOrder> orders = unitWithOrder.unit().getOrders();
                orders.forEach(order -> {
                    if (order.getTargetedWorldSpacePosition().isEmpty()) {
                        return;
                    }
                    Optional<AbilityData> maybeAbilityData = data.gameData().getAbility(order.getAbility());
                    maybeAbilityData.filter(abilityData -> abilityData.isBuilding()).ifPresent(buildingAbilityData -> {
                        Optional<UnitType> maybeUnitType = data.gameData().getUnitBuiltByAbilility(buildingAbilityData.getAbility());

                        maybeUnitType.ifPresent(unitType ->
                                updateMutableGridForStructure(data,
                                        order.getTargetedWorldSpacePosition().get().toPoint2d(),
                                        unitType));
                    });
                });
            });
        }
    }

    /**
     * Returns the modified footprint of a structure given its type.
     * This accounts for things like addons, minimum around production structures, etc.
     *
     * @param existingPosition
     * @param existingFootprint
     * @param unitType
     * @return Pair where the left is the new position, right is the new width/height.
     */
    private Pair<Point2d, Point2d> modifyFootprint(Point2d existingPosition, Point2d existingFootprint, UnitType unitType) {
        if (unitType == Units.TERRAN_BARRACKS ||
                unitType == Units.TERRAN_FACTORY ||
                unitType == Units.TERRAN_STARPORT) {
            // Modify the footprint of terran production buildings to be larger,
            // to accommodate the addon.
            return Pair.of(
                    existingPosition.add(1f, 0f),
                    existingFootprint.add(2f, 0f));
        }
        return Pair.of(existingPosition, existingFootprint);
    }

    public Grid<Boolean> getMutableFreePlacementGrid() {
        return this.mutableFreePlacementGrid;
    }
}
