package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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
            t.x = x;
            t.y = y;
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
        rampMaxHeights.forEach((rampId, rampMaxHeight) -> {
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
            int rampMinHeight = rampMinHeights.get(rampId);

            Ramp ramp = new Ramp(rampId,
                    rampPoints,
                    topOfRampPoints,
                    Ramp.calculateDirection(rampPoints, topOfRampPoints),
                    (int)((rampMaxHeight + rampMinHeight) / 2.0));
            System.out.println("Ramp " + rampId + " is facing " + ramp.getRampDirection().name() + " and midheight " + ramp.getRampMidHeight());
            mapOfRamps.put(rampId, ramp);
        });

        Grid<Integer> distanceTransformGrid = distanceTransform(pathing, result);
        Set<Point2d> confirmedMaxima = findLocalMaximumAndConfirmedMaxima(distanceTransformGrid, result);
        Map<Integer, Region> regions = floodFillRegions(confirmedMaxima, mapOfRamps, result);

        return new AnalysisResults(result, mapOfRamps, topOfRampLocations.keySet(), pathableTiles, regions);
    }

    private static Grid<Integer> distanceTransform(Grid<Integer> pathing, Grid<Tile> output) {
        Grid<Integer> result = new InMemoryGrid(Integer.class, pathing.getWidth(), pathing.getHeight(), () -> 0);
        // Using L1 distance transformation.
        for (int x = 0; x < result.getWidth(); ++x) {
            for (int y = 0; y < result.getHeight(); ++y) {
                if ((pathing.get(x, y) & 0xFF) == 0) {
                    result.set(x, y, 0);
                } else {
                    result.set(x, y, 255);
                }
            }
        }

        for (int x = 1; x < result.getWidth(); ++x) {
            for (int y = 1; y < result.getHeight(); ++y) {
                int value = Math.min(result.get(x, y), Math.min(result.get(x-1, y) + 1, result.get(x, y-1) + 1));
                result.set(x, y, value);
                Tile t = output.get(x, y);
                t.distanceToBorder = value & 0xFF;
            }
        }

        for (int x = result.getWidth() - 2; x >= 0; --x) {
            for (int y = result.getHeight() - 2; y >= 0; --y) {
                int value = Math.min(result.get(x, y), Math.min(result.get(x+1, y) + 1, result.get(x, y+1) + 1));
                result.set(x, y, value);
                Tile t = output.get(x, y);
                t.distanceToBorder = value & 0xFF;
            }
        }

        return result;
    }

    private static Set<Point2d> findLocalMaximumAndConfirmedMaxima(Grid<Integer> distanceTransformGrid, Grid<Tile> result) {
        // we don't test on the edges as an implementation detail, as it's usually outside the map +
        // should never be a maximum.
        // this is super naive and probably needs improvement to be run live, but is fine for
        // static analysis.
        Multimap<Integer, Point2d> localMaximums = ArrayListMultimap.create();
        for (int x = 1; x < result.getWidth() - 1; ++x) {
            for (int y = 1; y < result.getHeight() - 1; ++y) {
                int myValue = distanceTransformGrid.get(x, y);
                if (myValue == 0) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x - 1, y)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x + 1, y)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x, y - 1)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x, y + 1)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x - 1, y - 1)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x + 1, y - 1)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x - 1, y + 1)) {
                    continue;
                }
                if (myValue < distanceTransformGrid.get(x + 1, y + 1)) {
                    continue;
                }
                result.get(x, y).isLocalMaximum = true;
                localMaximums.put(myValue, Point2d.of(x, y));
            }
        }
        if (localMaximums.size() == 0) {
            return null;
        }
        OptionalInt maxLocalMaximum = localMaximums.keySet().stream().mapToInt(key -> key.intValue()).max();
        Set<Point2d> removedLocalMaxima = new HashSet<>();
        Set<Point2d> confirmedLocalMaxima = new HashSet<>();
        // Starting from the highest DT value, remove all other local maximums less than it, within the radius.
        for (int i = maxLocalMaximum.getAsInt(); i >= 0; --i) {
            Set<Point2d> localMaximaWithValue = new HashSet<>(localMaximums.get(i));
            localMaximaWithValue.removeAll(removedLocalMaxima);
            Set<Point2d> lowerMaxima = new HashSet<>();
            for (int j = 0; j < i; ++j) {
                lowerMaxima.addAll(localMaximums.get(j));
                lowerMaxima.removeAll(removedLocalMaxima);
            }
            int finalI = i;
            localMaximaWithValue.forEach(currentMaxima -> {
                lowerMaxima.forEach(otherMaxima -> {
                    if (currentMaxima.distance(otherMaxima) <= finalI * 1.5f) {
                        removedLocalMaxima.add(otherMaxima);
                    }
                });
            });
            confirmedLocalMaxima.addAll(localMaximaWithValue);
        }
        System.out.println("Filtered " + removedLocalMaxima.size() + " maxima");
        System.out.println("Remaining local maxima = " + confirmedLocalMaxima.size() + "/" + localMaximums.size());
        confirmedLocalMaxima.forEach(confirmedMaxima -> {
           result.get((int)confirmedMaxima.getX(), (int)confirmedMaxima.getY()).isPostFilteredLocalMaximum = true;
        });
        // group contiguous maxima
        int currentMaximaId = 0;
        Map<Point2d, Integer> maximaToId = new HashMap<>();
        if (confirmedLocalMaxima.size() > 0) {
            Queue<Point2d> openMaxima = new LinkedList<>();
            Set<Point2d> unexploredMaxima = new HashSet<>(confirmedLocalMaxima);
            openMaxima.add(confirmedLocalMaxima.stream().findFirst().get());
            while (openMaxima.isEmpty() == false) {
                Point2d point = openMaxima.poll();
                unexploredMaxima.remove(point);

                int x = (int)point.getX(), y = (int)point.getY();
                maximaToId.put(point, currentMaximaId);
                Tile t = result.get(x, y);
                t.regionId = currentMaximaId;
                result.set(x, y, t);

                // Explore neighbours of point.
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        Point2d neighbour = Point2d.of(x + dx, y + dy);
                        if (unexploredMaxima.contains(neighbour)) {
                            unexploredMaxima.remove(neighbour);
                            openMaxima.add(neighbour);
                        }
                    }
                }
                if (openMaxima.isEmpty()) {
                    // start exploring other ramp tiles
                    ++currentMaximaId;
                    if (unexploredMaxima.size() > 0) {
                        openMaxima.add(unexploredMaxima.stream().findFirst().get());
                    }
                }
            }
        }
        return confirmedLocalMaxima;
    }

    private static Map<Integer, Region> floodFillRegions(Set<Point2d> confirmedMaxima, Map<Integer, Ramp> mapOfRamps, Grid<Tile> grid) {
        PriorityQueue<Tile> queue = new PriorityQueue<>(Comparator.comparingInt(tile -> -tile.distanceToBorder));
        AtomicInteger tempCounter = new AtomicInteger();
        Map<Integer, Point2d> centrePoints = new HashMap<>();
        confirmedMaxima.forEach(maxima -> {
            Tile value = grid.get((int)maxima.getX(), (int)maxima.getY());
            queue.add(value);
            /*if (value.regionId == -1) {
                value.regionId = tempCounter.getAndIncrement();
            }*/
            centrePoints.put(value.regionId, Point2d.of(maxima.getX(), maxima.getY()));
        });
        // Add ramps as their own regions.
        Multimap<Integer, Tile> regions = ArrayListMultimap.create();
        Map<Integer, Integer> regionIdToRampId = new HashMap<>();
        Map<Integer, Integer> regionIdToCumulativeHeight = new HashMap<>();
        mapOfRamps.forEach((rampId, ramp) -> {
           ramp.getRampTiles().forEach(rampTile -> {
               Tile tile = grid.get((int)rampTile.getX(), (int)rampTile.getY());
               tile.regionId = 1000 + rampId;
               regions.put(1000 + rampId, tile);
               regionIdToRampId.put(tile.regionId, rampId);
               centrePoints.put(tile.regionId, ramp.calculateMidpoint());
           });
        });
        Set<Tile> alreadyEnqueued = new HashSet<>();
        SetMultimap<Integer, Integer> connectedRegions = HashMultimap.create();
        final BiConsumer<Integer, Tile> maybeEnqueue = (regionId, tile) -> {
            if (tile.regionId != -1 && tile.regionId != regionId) {
                // Found a connection.
                connectedRegions.put(tile.regionId, regionId);
                connectedRegions.put(regionId, tile.regionId);
            }
            if (alreadyEnqueued.contains(tile)) {
                return;
            }
            if (!tile.pathable) {
                return;
            }
            if (tile.regionId == -1) {
                tile.regionId = regionId;
                alreadyEnqueued.add(tile);
                queue.add(tile);
            }
        };
        // Floodfill to expand the regions.
        while (queue.size() > 0) {
            Tile head = queue.poll();
            regions.put(head.regionId, head);
            regionIdToCumulativeHeight.put(head.regionId,
                    regionIdToCumulativeHeight.getOrDefault(head.regionId, 0) + head.terrain);
            maybeEnqueue.accept(head.regionId, grid.get(head.x - 1, head.y - 1));
            maybeEnqueue.accept(head.regionId, grid.get(head.x, head.y - 1));
            maybeEnqueue.accept(head.regionId, grid.get(head.x + 1, head.y - 1));
            maybeEnqueue.accept(head.regionId, grid.get(head.x - 1, head.y));
            maybeEnqueue.accept(head.regionId, grid.get(head.x + 1, head.y));
            maybeEnqueue.accept(head.regionId, grid.get(head.x - 1, head.y + 1));
            maybeEnqueue.accept(head.regionId, grid.get(head.x, head.y + 1));
            maybeEnqueue.accept(head.regionId, grid.get(head.x + 1, head.y + 1));
        }
        Map<Integer, Integer> regionIdToAverageHeight = new HashMap<>();
        regionIdToCumulativeHeight.forEach((regionId, cumulativeHeight) -> {
            regionIdToAverageHeight.put(regionId, cumulativeHeight / regions.get(regionId).size());
        });

        // Get all the ramps and mark high/low grounds.
        Multimap<Integer, Integer> regionIsOnHighGroundOf = HashMultimap.create();
        Multimap<Integer, Integer> regionIsOnLowGroundOf = HashMultimap.create();
        mapOfRamps.values().forEach((ramp) -> {
            ramp.getTiles().stream().findFirst().ifPresent(firstTile -> {
                int regionId = grid.get((int)firstTile.getX(), (int)firstTile.getY()).regionId;
                int rampMidpointHeight = ramp.getRampMidHeight();
                Set<Integer> highGrounds = new HashSet<>();
                Set<Integer> lowGrounds = new HashSet<>();
                connectedRegions.get(regionId).forEach(connectedRegionId -> {
                    int connectedRegionHeight = regionIdToAverageHeight.get(connectedRegionId);
                    if (connectedRegionHeight > rampMidpointHeight) {
                        // connected region is on our high ground
                        highGrounds.add(connectedRegionId);
                        regionIsOnHighGroundOf.put(connectedRegionId, regionId);
                        regionIsOnLowGroundOf.put(regionId, connectedRegionId);
                    }
                    if (connectedRegionHeight < rampMidpointHeight) {
                        lowGrounds.add(connectedRegionId);
                        regionIsOnLowGroundOf.put(connectedRegionId, regionId);
                        regionIsOnHighGroundOf.put(regionId, connectedRegionId);
                    }
                    highGrounds.forEach(highGroundRegionId -> {
                        lowGrounds.forEach(lowGroundRegionId -> {
                            System.out.println("Region " + highGroundRegionId + " >> " + lowGroundRegionId);
                            System.out.println("Region " + lowGroundRegionId + " << " + highGroundRegionId);
                            regionIsOnLowGroundOf.put(lowGroundRegionId, highGroundRegionId);
                            regionIsOnHighGroundOf.put(highGroundRegionId, lowGroundRegionId);
                        });
                    });
                });

            });
        });

        Map<Integer, Region> result = new HashMap<>();
        regions.keySet().forEach(regionId -> {
            List<Point2d> tiles = regions.get(regionId).stream().map(tile -> Point2d.of(tile.x, tile.y))
                    .collect(Collectors.toList());
            Region newRegion = ImmutableRegion.builder()
                    .regionId(regionId)
                    .addAllTiles(tiles)
                    .rampId(Optional.ofNullable(regionIdToRampId.get(regionId)))
                    .connectedRegions(connectedRegions.get(regionId))
                    .centrePoint(centrePoints.get(regionId))
                    .addAllOnHighGroundOfRegions(regionIsOnHighGroundOf.get(regionId))
                    .addAllOnLowGroundOfRegions(regionIsOnLowGroundOf.get(regionId))
                    .regionBounds(
                            TileSet.calculateBounds(tiles).orElseThrow(() ->
                                    new IllegalArgumentException("Region with no tiles.")))
                    .build();
            result.put(regionId, newRegion);
        });

        return result;
    }

    private static boolean isTerrainHeightNearMaxHeight(int terrainHeight, int maxHeight) {
        return terrainHeight >= maxHeight - 2;
    }
}
