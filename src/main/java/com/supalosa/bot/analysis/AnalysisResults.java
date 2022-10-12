package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.utils.Grid;

import java.util.Map;
import java.util.Set;

public class AnalysisResults {
    private final Grid<Tile> grid;
    private final Map<Integer, Ramp> ramps;
    private final Set<Point2d> topOfRamps;
    public AnalysisResults(Grid<Tile> grid, Map<Integer,Ramp> ramps, Set<Point2d> topOfRamps) {
        this.grid = grid;
        this.ramps = ramps;
        this.topOfRamps = topOfRamps;
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
}
