package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;

import java.util.*;
import java.util.stream.Collectors;

public class Analysis {

    public static AnalysisResults run(Point2d startLocation, Grid<Integer> terrain, Grid<Integer> pathing, Grid<Integer> placement) {
        AnalysisResults results = floodFill(startLocation, terrain, pathing, placement);

        return results;
    }

    public static AnalysisResults floodFill(Point2d startLocation, Grid<Integer> terrain, Grid<Integer> pathing, Grid<Integer> placement) {
        Grid<Tile> result = new InMemoryGrid(Tile.class, terrain.getWidth(), terrain.getHeight(), () -> new Tile());
        Queue<Point2d> points = new LinkedList<>();
        Set<Point2d> explored = new HashSet<>();
        points.add(startLocation);
        explored.add(startLocation);
        Set<Point2d> rampLocations = new HashSet<>();
        // Border tiles are those reached from pathable tiles only.
        Set<Point2d> borderTiles = new HashSet<>();
        int iterations = 0;
        int initialTerrainValue = -1;
        int pathableTiles = 0;

        while (!points.isEmpty() && (++iterations) < 100000) {
            Point2d newPoint = points.poll();
            int x = (int)newPoint.getX(), y = (int)newPoint.getY();
            int terrainValue = terrain.get(x, y) & 0xFF;
            int pathingValue = pathing.get(x, y) & 0xFF;
            int placementValue = placement.get(x, y) & 0xFF;

            if (terrainValue == 0 && pathingValue == 0 && placementValue == 0) {
                continue;
            }
            if (pathingValue > 0) {
                pathableTiles++;
            }
            if (initialTerrainValue == -1) {
                initialTerrainValue = terrainValue;
            }
            boolean isRamp = false;
            // terrain, pathing, placement
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int terrainValueN = terrain.get(x + dx, y + dy) & 0xFF;
                    Point2d neighbourPoint = Point2d.of(x + dx, y + dy);
                    if (!explored.contains(neighbourPoint)) {
                        points.add(neighbourPoint);
                        explored.add(neighbourPoint);
                    }
                    // look for tiles that are not buildable but pathable, and have
                    // height change between it and its neighbours
                    if (placementValue == 0 && pathingValue > 0) {
                        if (terrainValueN != terrainValue) {
                            isRamp = true;
                            rampLocations.add(newPoint);
                        }
                    }
                    if (pathingValue > 0) {
                        // if current tile is pathable, check if neighbour is not pathable
                        // the neighbour tile could get 'added' multiple times :/
                        int pathingValueN = pathing.get(x + dx, y + dy) & 0xFF;
                        if (pathingValueN == 0) {
                            borderTiles.add(neighbourPoint);
                        }
                    }
                }
            }
            Tile t = result.get(x, y);
            t.terrain = terrainValue;
            t.pathable = pathingValue > 0;
            t.placeable = placementValue > 0;
            if (isRamp) {
                t.isRamp = true;
            }
            result.set(x, y, t);
        }

        if (!points.isEmpty()) {
            System.out.println("Map analysis ended early with " + points.size() + " points remaining");
        }

        // group contiguous ramps
        int currentRampId = 0;
        Map<Point2d, Integer> rampToRampId = new HashMap<>();
        Map<Integer, Integer> rampMinHeights = new HashMap<>();
        Map<Integer, Integer> rampMaxHeights = new HashMap<>();
        int thisRampMinHeight = Integer.MAX_VALUE;
        int thisRampMaxHeight = Integer.MIN_VALUE;
        if (rampLocations.size() > 0) {
            Queue<Point2d> openRamps = new LinkedList<>();
            Set<Point2d> unexploredRamps = new HashSet<>(rampLocations);
            openRamps.add(rampLocations.stream().findFirst().get());
            while (openRamps.isEmpty() == false) {
                Point2d point = openRamps.poll();
                unexploredRamps.remove(point);

                int x = (int)point.getX(), y = (int)point.getY();
                rampToRampId.put(point, currentRampId);
                Tile t = result.get(x, y);
                t.rampId = currentRampId;
                result.set(x, y, t);
                if (t.terrain > thisRampMaxHeight) {
                    thisRampMaxHeight = t.terrain;
                }
                if (t.terrain < thisRampMinHeight) {
                    thisRampMinHeight = t.terrain;
                }

                // Explore neighbours of point.
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        Point2d neighbour = Point2d.of(x + dx, y + dy);
                        if (unexploredRamps.contains(neighbour)) {
                            unexploredRamps.remove(neighbour);
                            openRamps.add(neighbour);
                        }
                    }
                }
                if (openRamps.isEmpty()) {
                    rampMinHeights.put(currentRampId, thisRampMinHeight);
                    rampMaxHeights.put(currentRampId, thisRampMaxHeight);
                    // start exploring other ramp tiles
                    ++currentRampId;
                    thisRampMinHeight = Integer.MAX_VALUE;
                    thisRampMaxHeight = Integer.MIN_VALUE;
                    if (unexploredRamps.size() > 0) {
                        openRamps.add(unexploredRamps.stream().findFirst().get());
                    }
                }
            }
        }

        // expand ramp neighbours. Mapping of ramp location -> ramp ID
        Map<Point2d, Integer> topOfRampLocations = new HashMap<Point2d, Integer>();
        rampLocations.forEach(rampLocation -> {
            int x = (int)rampLocation.getX(), y = (int)rampLocation.getY();

            Tile t = result.get(x, y);
            int rampHeight = t.terrain;
            int rampMaxHeight = rampMaxHeights.get(t.rampId);
            int rampMinHeight = rampMinHeights.get(t.rampId);
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    Tile nT = result.get(x + dx, y + dy);
                    int neighbourHeight = nT.terrain;
                    boolean neighbourBuildable = nT.placeable;
                    Point2d neighbourPoint = Point2d.of(x + dx, y + dy);
                    //if (neighbourBuildable > 0 && neighbourHeight >= rampHeight && !rampLocations.contains(neighbourPoint)) {
                    if (neighbourBuildable
                            && isTerrainHeightNearMaxHeight(rampHeight, rampMaxHeight)
                            && !rampLocations.contains(neighbourPoint)) {
                        topOfRampLocations.put(neighbourPoint, t.rampId);
                        nT.isTopOfRamp = true;
                        result.set(x + dx, y + dy, nT);
                    }
                }
            }
        });

        // Expand border tiles to find jumpable cliffs.
        borderTiles.forEach(borderTile -> {
            int x = (int)borderTile.getX();
            int y = (int)borderTile.getY();
            boolean isTraversable = false;
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = 0; dy <= 1; ++dy) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    // Neighbour
                    int nx = x + dx, ny = y + dy;
                    // Opposite
                    int ox = x - dx, oy = y - dy;
                    int nTerrain = terrain.get(nx, ny) & 0xFF;
                    int oTerrain = terrain.get(ox, oy) & 0xFF;
                    boolean nPathable = (pathing.get(nx, ny) & 0xFF) > 0;
                    boolean oPathable = (pathing.get(ox, oy) & 0xFF) > 0;
                    if (nPathable && oPathable) {
                        isTraversable = true;
                        break;
                    }
                }
                if (isTraversable) {
                    break;
                }
            }
            if (isTraversable) {
                Tile t = result.get(x, y);
                t.traversableCliff = true;
                result.set(x, y, t);
            }
        });

        // Construct ramp data objects.
        Map<Integer, Ramp> mapOfRamps = new HashMap<>();
        rampMaxHeights.keySet().forEach(rampId -> {
            // TODO optimise this
            Set<Point2d> rampPoints = rampLocations
                    .stream()
                    .filter(ramp -> rampToRampId.get(ramp) == rampId)
                    .collect(Collectors.toSet());
            Set<Point2d> topOfRampPoints = topOfRampLocations.entrySet()
                    .stream()
                    .filter(ramp -> ramp.getValue() == rampId)
                    .map(ramp -> ramp.getKey())
                    .collect(Collectors.toSet());
            Ramp ramp = new Ramp(rampId, rampPoints, topOfRampPoints, Ramp.calculateDirection(rampPoints, topOfRampPoints));
            System.out.println("Ramp " + rampId + " is facing " + ramp.getRampDirection().name());
            mapOfRamps.put(rampId, ramp);
        });

        return new AnalysisResults(result, mapOfRamps, topOfRampLocations.keySet(), pathableTiles);
    }

    private static boolean isTerrainHeightNearMaxHeight(int terrainHeight, int maxHeight) {
        return terrainHeight >= maxHeight - 2;
    }
}
