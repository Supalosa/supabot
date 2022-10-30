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
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Ramp;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.pathfinding.BreadthFirstSearch;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
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

    private List<Unit> myStructures = new ArrayList<>();
    private long myStructuresUpdatedAt = 0L;

    // Grid of tiles available for free placement (i.e. not reserved tiles).
    // This grid is STATIC and should not be modified during the game.
    private final Grid<Boolean> staticFreePlacementGrid;
    // This grid can (and should) be modified to mark tiles that should not be built on.
    // Each tile represents a tag that the tile is reserved for. If the value is equal to `BUILDABLE`, then it is
    // buildable.
    private final Grid<Tag> mutableFreePlacementGrid;
    private static final Tag BUILDABLE = Tag.of(0L);
    private static final Tag NOT_BUILDABLE = Tag.of(1L);

    private List<DebugStructureFootprint> debugStructureFootprints = new ArrayList<>();
    private long debugStructureFootprintsResetAt = 0L;

    class DebugStructureFootprint {
        Point2d position;
        int width;
        int height;
        int index;
        boolean success;

        public DebugStructureFootprint(Point2d origin, int width, int height, boolean success) {
            this.position = origin;
            this.width = width;
            this.height = height;
            this.success = success;
        }
    }

    public StructurePlacementCalculator(AnalysisResults mapAnalysisResult, GameData gameData, Point2d start) {
        this.mapAnalysisResult = mapAnalysisResult;
        this.gameData = gameData;
        this.start = start;
        this.staticFreePlacementGrid = InMemoryGrid.copyOf(
                Boolean.class,
                mapAnalysisResult.getGrid(), () -> true, tile -> tile.placeable);
        this.mutableFreePlacementGrid = new InMemoryGrid<>(
                Tag.class,
                mapAnalysisResult.getGrid().getWidth(),
                mapAnalysisResult.getGrid().getHeight(),
                () -> BUILDABLE);
    }

    void updateFreePlacementGrid() {
        if (firstSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = firstSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 2, 2, false);
            });
        }
        if (secondSupplyDepotLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = secondSupplyDepotLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 2, 2, false);
            });
        }
        if (barracksWithAddonLocation.isPresent()) {
            // has been calculated
            Optional<Point2d> maybeLocation = barracksWithAddonLocation.get();
            maybeLocation.ifPresent(location -> {
                updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)location.getX(), (int)location.getY(), 3, 3, false);
            });
        }
    }

    /**
     * Marks the tiles under a structure positioned at (x,y) with width (w,h) as unplaceable.
     */
    <T> void updatePlacementGridWithFootprint(Grid<T> grid, int x, int y, int w, int h, T value) {
        // Note that a structure's origin is at its centre (biased to northeast for even numbers, hence the `ceil`)
        int xStart = (int)Math.ceil(x - w / 2);
        int yStart = (int)Math.ceil(y - h / 2);
        for (int xx = xStart; xx < xStart + w; ++xx) {
            for (int yy = yStart; yy < yStart + h; ++yy) {
                if (grid.isInBounds(xx, yy)) {
                    grid.set(xx, yy, value);
                }
            }
        }
    }

    private Pair<Point2d, Point2d> getFootprint(Point2d point, int w, int h) {
        int x = (int)point.getX();
        int y = (int)point.getY();
        int xStart = (int)Math.ceil(x - w / 2);
        int yStart = (int)Math.ceil(y - h / 2);
        return Pair.of(Point2d.of(xStart, yStart), Point2d.of(xStart + w, yStart + h));
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
                if (mutableFreePlacementGrid.get(x, y) != BUILDABLE) {
                    agent.debug().debugBoxOut(
                            point3d.sub(-0.1f, -0.1f, 0.2f),
                            point3d.sub(-0.9f, -0.9f, -0.2f), Color.of(156, 156, 156));
                }
            }
        }
        // Draw boxes around every position we tested in the last second.
        debugStructureFootprints.forEach(footprint -> {
            Pair<Point2d, Point2d> bounds = getFootprint(footprint.position, footprint.width, footprint.height);
            float height1 = Math.min(agent.observation().terrainHeight(bounds.getLeft()) + 0.05f,
                    agent.observation().terrainHeight(bounds.getRight()) + 0.05f);
            float height2 = height1 + 1f;
            Point point1 = Point.of(bounds.getLeft().getX(), bounds.getLeft().getY(), height1);
            Point point2 = Point.of(bounds.getRight().getX(), bounds.getRight().getY(), height2);
            Color color = footprint.success ? Color.of(64, 255, 64) : Color.of(255, 64, 64);
            agent.debug().debugBoxOut(point1, point2, color);
        });
    }

    public void onExpansionsCalculated(List<Expansion> expansionLocations) {
        // Block out the town hall position and the space between the resources and the town hall to prevent
        // blockage.
        expansionLocations.forEach(expansion -> {
           updatePlacementGridWithFootprint(staticFreePlacementGrid,
                   (int)expansion.position().getX(),
                   (int)expansion.position().getY(), 5, 5,
                   false);
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
                   updatePlacementGridWithFootprint(staticFreePlacementGrid, (int)position.getX(), (int)position.getY(), 3, 3, false);
                });
            }
        });
    }

    // does NOT query for placement but will never suggest something that overlaps with a reserved tile.
    public Optional<Point2d> suggestLocationForFreePlacement(AgentWithData data,
                                                             Point2d origin,
                                                             int structureWidth,
                                                             int structureHeight,
                                                             Optional<PlacementRules> placementRules) {
        PlacementRules.Region region = placementRules
                .map(PlacementRules::regionType)
                .orElse(PlacementRules.Region.PLAYER_BASE_ANY);
        Optional<UnitType> unitTypeNearFilter = placementRules.flatMap(PlacementRules::near);
        final Point2d searchOrigin = unitTypeNearFilter.flatMap(type -> findMyStructureOfType(type)).orElse(origin);
        // Reserve the location after we suggest it. It will be wiped clear in the next structure grid update if
        // it doesn't actually get used.
        Optional<Point2d> suggestedLocation = Optional.empty();
        switch (region) {
            case PLAYER_BASE_ANY:
            case PLAYER_BASE_BORDER:
            case PLAYER_BASE_CENTRE:
                suggestedLocation = findAnyPlacementNearBase(searchOrigin, placementRules, structureWidth, structureHeight, data);
                break;
            case EXPANSION:
                suggestedLocation = findExpansionPlacement(searchOrigin, structureWidth, structureHeight, data);
                break;
            case MAIN_RAMP_SUPPLY_DEPOT_1:
                suggestedLocation = getFirstSupplyDepotLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules, data))
                        .orElseGet(() -> findAnyPlacementNearBase(searchOrigin, placementRules, structureWidth, structureHeight, data));
                break;
            case MAIN_RAMP_SUPPLY_DEPOT_2:
                suggestedLocation = getSecondSupplyDepotLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules, data))
                        .orElseGet(() -> findAnyPlacementNearBase(searchOrigin, placementRules, structureWidth, structureHeight, data));
                break;
            case MAIN_RAMP_BARRACKS_WITH_ADDON:
                suggestedLocation = getFirstBarracksWithAddonLocation()
                        .map(point -> checkSpecificPlacement(point, structureWidth, structureHeight, placementRules, data))
                        .orElseGet(() -> findAnyPlacementNearBase(searchOrigin, placementRules, structureWidth, structureHeight, data));
                break;
            default:
                throw new IllegalArgumentException("Unsupported placement region logic: " + region);
        }
        if (suggestedLocation.isPresent()) {
            updatePlacementGridWithFootprint(mutableFreePlacementGrid,
                    (int)suggestedLocation.get().getX(),
                    (int)suggestedLocation.get().getY(),
                    structureWidth,
                    structureHeight,
                    NOT_BUILDABLE
                    );
        }
        return suggestedLocation;
    }

    private Optional<Point2d> findMyStructureOfType(UnitType type) {
        return myStructures.stream().filter(unit -> unit.getType().equals(type))
                .findFirst()
                .map(Unit::getPosition)
                .map(Point::toPoint2d);
    }

    private static final int MAX_FREE_PLACEMENT_ITERATIONS = 80;
    private static final int MAX_ADJACENT_PLACEMENT_ITERATIONS = 10;
    private static final int DEFAULT_MAX_PLACEMENT_VARIATION = 20;

    private Optional<Point2d> findAnyPlacementNearBase(Point2d origin,
                                                       Optional<PlacementRules> placementRules,
                                                       int structureWidth,
                                                       int structureHeight,
                                                       AgentWithData data) {
        int actualSearchRadius = placementRules.map(PlacementRules::maxVariation).orElse(DEFAULT_MAX_PLACEMENT_VARIATION);
        List<Point2d> nearbyStructures = myStructures.stream()
                .map(myStructure -> myStructure.getPosition().toPoint2d())
                .filter(myStructurePoint2d -> {
            return myStructurePoint2d.distance(origin) < actualSearchRadius;
        }).collect(Collectors.toList());
        PlacementRules.Region placementRegion = placementRules.map(PlacementRules::regionType).orElse(PlacementRules.Region.PLAYER_BASE_ANY);
        if (placementRegion == PlacementRules.Region.PLAYER_BASE_BORDER || placementRegion == PlacementRules.Region.PLAYER_BASE_CENTRE) {
            // Repeatedly test until we find a position at the minimum distance from the border of the region.
            Optional<Region> maybeRegion = data.mapAwareness().getRegionDataForPoint(origin).map(RegionData::region);
            if (maybeRegion.isEmpty()) {
                return findAnyPlacement(origin, structureWidth, structureHeight, actualSearchRadius,
                        nearbyStructures);
            } else {
                return findPlacementRelativeToBorder(structureWidth, structureHeight, data, maybeRegion, placementRegion);
            }
        } else {
            return findAnyPlacement(origin, structureWidth, structureHeight, actualSearchRadius,
                    nearbyStructures);
        }
    }

    private Optional<Point2d> findPlacementRelativeToBorder(int structureWidth, int structureHeight, AgentWithData data, Optional<Region> maybeRegion, PlacementRules.Region placementRegion) {
        Region region = maybeRegion.get();
        Pair<Point2d, Point2d> bounds = region.regionBounds();
        Point2d bottomLeftBound = bounds.getLeft();
        Point2d topRightBound = bounds.getRight();
        int bestCandidateScore = Integer.MIN_VALUE;
        Optional<Point2d> bestCandidate = Optional.empty();
        // Start at the middle of the region and start walking towards the border.
        final Point2d startPoint = region.centrePoint();
        Point2d testPoint = startPoint;
        for (int i = 0; i < MAX_FREE_PLACEMENT_ITERATIONS; ++i) {
            Point2d thisPoint = testPoint;
            Optional<Tile> thisTile = data.mapAnalysis().flatMap(analysis -> analysis.getTile(thisPoint));
            if (thisTile.isEmpty()) {
                testPoint = startPoint;
                continue;
            }
            if (canPlaceAt(testPoint, structureWidth, structureHeight)) {
                int score = placementRegion == PlacementRules.Region.PLAYER_BASE_BORDER ?
                        -thisTile.get().distanceToBorder :
                        thisTile.get().distanceToBorder;
                if (score > bestCandidateScore) {
                    bestCandidateScore = score;
                    bestCandidate = Optional.of(thisPoint);
                }
            }
            // Walk randomly towards a point with lower distance to border (since we reset at the middle)
            List<Point2d> candidates = new ArrayList<>();
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    Point2d maybePoint = testPoint.add(dx, dy);
                    if (region.getTiles().contains(maybePoint)) {
                        Optional<Tile> nextTile = data.mapAnalysis().flatMap(analysis -> analysis.getTile(maybePoint));
                        nextTile.ifPresent(tile -> {
                            if (tile.distanceToBorder <= thisTile.get().distanceToBorder) {
                                candidates.add(maybePoint);
                            }
                        });
                    }
                }
            }
            if (candidates.isEmpty()) {
                testPoint = startPoint;
            } else {
                testPoint = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            }
        }
        return bestCandidate;
    }

    private Optional<Point2d> findAnyPlacement(Point2d origin, int structureWidth, int structureHeight,
                                                       int actualSearchRadius, List<Point2d> nearbyStructures) {
        for (int i = 0; i < MAX_FREE_PLACEMENT_ITERATIONS; ++i) {
            // prefer to place next to an existing structure.
            Point2d candidate;
            if (nearbyStructures.size() > 0 && i < MAX_ADJACENT_PLACEMENT_ITERATIONS) {
                if (ThreadLocalRandom.current().nextBoolean()) {
                    // up-down
                    candidate = nearbyStructures.get(getRandomInteger(0, nearbyStructures.size()))
                            .add(0, structureHeight * getRandomSign());
                } else {
                    // left-right
                    candidate = nearbyStructures.get(getRandomInteger(0, nearbyStructures.size()))
                            .add(structureWidth * getRandomSign(), 0);
                }
            } else {
                // Initially we will search closer to the querying worker. The longer we search, the more willing we
                // are to place something far away.
                int testedRadius = (int) Math.max(1, actualSearchRadius * Math.min(1f, (float) i / (float) MAX_FREE_PLACEMENT_ITERATIONS));
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
                                                     Optional<PlacementRules> placementRules,
                                                     AgentWithData data) {
        if (canPlaceAtIgnoringStaticGrid(point, structureWidth, structureHeight)) {
            return Optional.of(point);
        } else if (placementRules.isPresent() && placementRules.get().maxVariation() > 0) {
            // Place near the target point.
            return findAnyPlacementNearBase(point, placementRules, structureWidth, structureHeight, data);
        }
        return Optional.empty();
    }

    public Optional<Point2d> suggestLocationForFreePlacement(AgentWithData data,
                                                             Point2d position,
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
        return suggestLocationForFreePlacement(data, position,
                (int)newFootprint.getX(),
                (int)newFootprint.getY(),
                placementRules).map(outputPosition ->
                outputPosition/*.add(outputOffset)*/);
    }

    private boolean canPlaceAt(Point2d origin, int width, int height) {
        return canPlaceAt(origin, width, height, true, Optional.empty());
    }

    private boolean canPlaceAtIgnoringStaticGrid(Point2d origin, int width, int height) {
        return canPlaceAt(origin, width, height, false, Optional.empty());
    }

    private boolean canPlaceAt(Point2d origin, int width, int height, boolean checkStatic, Optional<Tag> forTag) {
        // Intercept the calls so we can log position checks.
        if (_canPlaceAt(origin, width, height, checkStatic, forTag)) {
            debugStructureFootprints.add(new DebugStructureFootprint(origin, width, height, true));
            return true;
        } else {
            debugStructureFootprints.add(new DebugStructureFootprint(origin, width, height, false));
            return false;
        }
    }

    private boolean _canPlaceAt(Point2d origin, int width, int height, boolean checkStatic, Optional<Tag> forTag) {
        int x = (int)origin.getX();
        int y = (int)origin.getY();
        int xStart = (int)Math.ceil(x - width / 2);
        int yStart = (int)Math.ceil(y - height / 2);
        for (int xx = xStart; xx < xStart + width; ++xx) {
            for (int yy = yStart; yy < yStart + height; ++yy) {
                if (checkStatic && staticFreePlacementGrid.get(xx, yy) == false) {
                    return false;
                }
                if (mutableFreePlacementGrid.get(xx, yy) != forTag.orElse(BUILDABLE)) {
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

    private void updateMutableGridForStructure(AgentData data, Unit unit) {
        updateMutableGridForStructure(data, unit.getPosition().toPoint2d(), unit.getType(), Optional.of(unit.getTag()));
    }
    private void updateMutableGridForStructure(AgentData data, Point2d position, UnitType unitType) {
        updateMutableGridForStructure(data, position, unitType, Optional.empty());
    }
    private void updateMutableGridForStructure(AgentData data, Point2d position, UnitType unitType, Optional<Tag> tag) {
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
            updatePlacementGridWithFootprint(mutableFreePlacementGrid, x, y, width, height, tag.orElse(NOT_BUILDABLE));
        });
    }

    public void onStep(AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > debugStructureFootprintsResetAt + 22L * 15) {
            debugStructureFootprints.clear();
            debugStructureFootprintsResetAt = gameLoop;
        }

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
                        unitInPool.unit());

                Optional<UnitTypeData> maybeUnitTypeData = data.gameData().getUnitTypeData(unitInPool.unit().getType());
                return maybeUnitTypeData.map(unitType ->
                    unitType.getAttributes().contains(UnitAttribute.STRUCTURE)
                ).orElse(false);
            }).stream().map(unitInPool -> unitInPool.unit()).collect(Collectors.toList());

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
            // to accommodate the addon and space to move units.
            return Pair.of(
                    existingPosition.add(1f, 0f),
                    existingFootprint.add(2f, 0f));
        }
        return Pair.of(existingPosition, existingFootprint);
    }

    public Grid<Tag> getMutableFreePlacementGrid() {
        return this.mutableFreePlacementGrid;
    }

    /**
     * Returns true if the given structure can fit an addon next to it.
     *
     * @param unit Unit to test.
     * @return True if that unit can fit an addon, false otherwise.
     */
    public boolean canFitAddon(Unit unit) {
        return canPlaceAtFor(unit, unit.getPosition().toPoint2d().add(3f, 0f), 2, 2);
    }

    /**
     * Returns true if a structure
     * @param unit
     * @param position
     * @param w
     * @param h
     * @return
     */
    private boolean canPlaceAtFor(Unit unit, Point2d position, int w, int h) {
        return _canPlaceAt(position, w, h, true, Optional.of(unit.getTag()));
    }
}
