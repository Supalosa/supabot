package com.supalosa.bot.pathfinding;

import com.supalosa.bot.analysis.Region;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RegionGraph extends SimpleWeightedGraph<Region, DefaultWeightedEdge> {
    public RegionGraph(Class<? extends DefaultWeightedEdge> edgeClass) {
        super(edgeClass);
    }

    public Optional<List<Region>> findPath(Region startRegion, Region endRegion) {
        AStarShortestPath<Region, DefaultWeightedEdge> pathfinder = new AStarShortestPath<>(
                this, (sourceVertex, targetVertex) -> sourceVertex.centrePoint().distance(targetVertex.centrePoint()));

        GraphPath<Region, DefaultWeightedEdge> path = pathfinder.getPath(startRegion, endRegion);
        if (path == null || path.getEndVertex() == null || !path.getEndVertex().equals(endRegion)) {
            return Optional.empty();
        }
        return Optional.of(new ArrayList<>(path.getVertexList()));
    }
}