package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.utils.Grid;

import java.util.*;

public class AnalysisResults {
    private final Grid<Tile> grid;
    private final Map<Integer, Ramp> ramps;
    private final Set<Point2d> topOfRamps;
    private final int pathableTiles;
    private final Map<Integer, Region> regions;

    public AnalysisResults(Grid<Tile> grid, Map<Integer, Ramp> ramps, Set<Point2d> topOfRamps, int pathableTiles, Map<Integer, Region> regions) {
        this.grid = grid;
        this.ramps = ramps;
        this.topOfRamps = topOfRamps;
        this.pathableTiles = pathableTiles;
        this.regions = regions;
    }

    public Set<Point2d> getTopOfRamps() {
        return topOfRamps;
    }

    public Grid<Tile> getGrid() {
        return grid;
    }

    public Ramp getRamp(int rampId) {
        return ramps.get(rampId);
    }

    public int getPathableTiles() {
        return pathableTiles;
    }

    public Region getRegion(int regionId) {
        return regions.get(regionId);
    }

    public Collection<Region> getRegions() {
        return regions.values();
    }

    public Optional<Tile> getTile(int x, int y) {
        return grid.isSet(x, y) ? Optional.of(grid.get(x, y)) : Optional.empty();
    }

    public Optional<Tile> getTile(Point2d point) {
        return this.getTile((int)point.getX(), (int)point.getY());
    }
}
