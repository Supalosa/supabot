package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.pathfinding.BreadthFirstSearch;

import java.util.Optional;

/**
 * Calculates where to place walls for different races.
 */
public class StructurePlacementCalculator {

    private AnalysisResults mapAnalysisResult;
    private Optional<Optional<Point2d>> firstSupplyDepot;

    public StructurePlacementCalculator(AnalysisResults mapAnalysisResult) {
        this.mapAnalysisResult = mapAnalysisResult;
        this.firstSupplyDepot = Optional.empty();
    }

    public Optional<Point2d> getFirstSupplyDepot(Point2d start) {
        if (firstSupplyDepot.isEmpty()) {
            firstSupplyDepot = Optional.of(calculateFirstSupplyDepotLocation(start));
        }
        return firstSupplyDepot.get();
    }

    Optional<Point2d> calculateFirstSupplyDepotLocation(Point2d start) {
        // Find the first ramp tile from the start.
        Grid<Tile> mapGrid = mapAnalysisResult.getGrid();
        Optional<Point2d> maybeNearestRamp = BreadthFirstSearch.getFirstPoint(start, mapGrid, tile -> tile.isRamp);
        if (maybeNearestRamp.isEmpty()) {
            // TODO: implement fallback if no ramp.
            return Optional.empty();
        } else {
            Point2d nearestRamp = maybeNearestRamp.get();
            Tile rampTile = mapGrid.get((int)nearestRamp.getX(), (int)nearestRamp.getY());
            System.out.println("We found ramp at " + maybeNearestRamp.get() + " with ID " + rampTile.rampId);
            Ramp theRamp = mapAnalysisResult.getRamp(rampTile.rampId);
            System.out.println("The ramp has " + theRamp.getRampTiles().size() + " tiles and " + theRamp.getTopOfRampTiles().size() + " top of ramp tiles");

            // get all the
            return Optional.empty();
        }
    }
}
